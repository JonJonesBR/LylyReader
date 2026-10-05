package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

enum class AdapterModo { LEITURA, EDICAO, ALTERACOES }

/**
 * Adapter do RecyclerView de parágrafos da leitura (modos leitura/edição/comparação de
 * alterações). Extraído de ReaderActivity (Fase 1) — segue o mesmo padrão de arquivo dedicado já
 * usado por AudiobookAdapter/QueueAdapter. Era uma `inner class`; os poucos pontos que dependiam
 * da Activity (getString, Toast, viewModel, o RecyclerView para o Snackbar) viraram parâmetros
 * de construtor.
 */
private val COR_SELECAO = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#D0C0FF"),
    TemaLeitura.ESCURO to Color.parseColor("#4A3070"),
    TemaLeitura.SEPIA  to Color.parseColor("#C8A870"),
    TemaLeitura.OLED   to Color.parseColor("#4A3070"),
)
private val COR_BUSCA_ATUAL = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#FFE082"),
    TemaLeitura.ESCURO to Color.parseColor("#5C4A20"),
    TemaLeitura.SEPIA  to Color.parseColor("#D4A040"),
    TemaLeitura.OLED   to Color.parseColor("#5C4A20"),
)
private val COR_BUSCA_MATCH = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#FFF8E1"),
    TemaLeitura.ESCURO to Color.parseColor("#3A3020"),
    TemaLeitura.SEPIA  to Color.parseColor("#E8D0A0"),
    TemaLeitura.OLED   to Color.parseColor("#3A3020"),
)
private val COR_SYNC = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#EDE9FF"),
    TemaLeitura.ESCURO to Color.parseColor("#2A2040"),
    TemaLeitura.SEPIA  to Color.parseColor("#E8D8B0"),
    TemaLeitura.OLED   to Color.parseColor("#2A2040"),
)

// IDs dos itens de menu no ActionMode de seleção de texto — nomeados (1001/1002 eram débito
// aceito no baseline do detekt; 1003/1004 são os itens novos).
private const val MENU_ITEM_EXCLUIR_OCORRENCIAS = 1001
private const val MENU_ITEM_SELECIONAR_PARAGRAFO = 1002
private const val MENU_ITEM_DESTACAR = 1003
private const val MENU_ITEM_DEFINIR = 1004
private const val MENU_ITEM_PRONUNCIA = 1005

class ParagrafoAdapter(
    private val activity: AppCompatActivity,
    private val recyclerView: RecyclerView,
    private val viewModel: ReaderViewModel,
    private val onLongPress: (Int) -> Unit,
    private val onToque: (Int) -> Unit,
    // T3.5 — destacar um trecho selecionado do parágrafo (posição, início, fim em char offset)
    private val onDestacarTrecho: (Int, Int, Int) -> Unit,
    // Dicionário de pronúncia — palavra selecionada
    private val onDefinirPronuncia: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        // A posição do parágrafo é sua identidade dentro de uma sessão de leitura (a lista
        // é sempre substituída por inteiro, nunca reordenada) — ajuda o RecyclerView a
        // reciclar/animar melhor em livros grandes.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = position.toLong()

    private var paragrafos: List<Paragrafo> = emptyList()
    private var selecaoInicio: Int? = null
    private var selecaoFim: Int? = null
    private var syncAtual: Int = -1
    private var progressPct: Float = 0f
    private var tamanhoFonte: Float = FONTE_PADRAO_SP
    private var espacamento: Float = ESPACAMENTO_PADRAO
    private var tema: TemaLeitura = TemaLeitura.CLARO
    private var fonteSerifada: Boolean = false
    var modo: AdapterModo = AdapterModo.LEITURA
        private set

    // Carregado uma única vez (não a cada bind) — fonte serifada opcional da leitura.
    private val typefaceSerifado: android.graphics.Typeface? by lazy {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.literata_regular)
        }.getOrNull()
    }

    // Para o modo ALTERACOES: parágrafos originais pré-computados
    private var paragrafosOriginais: List<Paragrafo> = emptyList()

    // Para o modo EDICAO: rastrear edições feitas pelo usuário
    private val edicoesAtuais = mutableMapOf<Int, String>()

    // T4.1 — Busca
    var buscaResultados: List<Int> = emptyList()
    var buscaIndiceAtual: Int = -1

    // T3.5 — Destaques: índice do parágrafo -> trechos destacados (char offsets locais ao texto)
    var destaques: Map<Int, List<IntRange>> = emptyMap()

    fun consumirEdicoes(): Map<Int, String> =
        edicoesAtuais.toMap().also { edicoesAtuais.clear() }

    // Lista de parâmetros já era longa antes (10, baselined) — o param novo (fonteSerifada,
    // T3.2) muda a assinatura exata e o detekt para de reconhecer o achado como conhecido.
    // Agrupar em uma classe de aparência exigiria reescrever o call site só por causa da
    // contagem do lint, sem reduzir complexidade real: mantém-se via named args.
    @Suppress("LongParameterList")
    fun atualizarEstado(
        paragrafos: List<Paragrafo>,
        selecaoInicio: Int?,
        selecaoFim: Int?,
        syncAtual: Int,
        progressPct: Float,
        tamanhoFonte: Float,
        espacamento: Float,
        tema: TemaLeitura,
        modo: AdapterModo = this.modo,
        paragrafosOriginais: List<Paragrafo> = this.paragrafosOriginais,
        fonteSerifada: Boolean = this.fonteSerifada
    ) {
        val mudouModo = this.modo != modo
        val mudouLista = this.paragrafos !== paragrafos || mudouModo
        val mudouAparencia = this.tamanhoFonte != tamanhoFonte ||
            this.espacamento != espacamento ||
            this.tema != tema ||
            this.selecaoInicio != selecaoInicio ||
            this.selecaoFim != selecaoFim ||
            this.paragrafosOriginais !== paragrafosOriginais ||
            this.fonteSerifada != fonteSerifada
        val syncAntigo = this.syncAtual
        this.paragrafos = paragrafos
        this.selecaoInicio = selecaoInicio
        this.selecaoFim = selecaoFim
        this.syncAtual = syncAtual
        this.progressPct = progressPct
        this.tamanhoFonte = tamanhoFonte
        this.espacamento = espacamento
        this.tema = tema
        this.modo = modo
        this.paragrafosOriginais = paragrafosOriginais
        this.fonteSerifada = fonteSerifada
        when {
            mudouLista -> notifyDataSetChanged()
            mudouAparencia -> notifyItemRangeChanged(0, paragrafos.size)
            modo == AdapterModo.LEITURA -> {
                // Atualização só de progresso/sync (leitura guiada, 20fps):
                // repinta apenas o parágrafo atual e o anterior — repintar a lista
                // inteira a cada tick era a causa do flicker (#3).
                // Sem parágrafo de sync (p. ex. depois de rolar com o dedo, que zera o sync) não há
                // nada a repintar: o fallback antigo repintava a LISTA INTEIRA a cada tick, e cada
                // repintura reinicia o estado de seleção dos TextViews — a causa de não conseguir
                // selecionar palavras depois de rolar.
                linkedSetOf(syncAntigo, syncAtual).filter { it in paragrafos.indices }
                    .forEach { notifyItemChanged(it) }
            }
            else -> notifyItemRangeChanged(0, paragrafos.size)
        }
    }

    override fun getItemViewType(position: Int) = when (modo) {
        AdapterModo.LEITURA    -> 0
        AdapterModo.EDICAO     -> 1
        AdapterModo.ALTERACOES -> 2
    }

    inner class VHLeitura(val tv: TextView) : RecyclerView.ViewHolder(tv) {
        /** Texto para o qual a seleção já foi (re)armada; evita reiniciá-la a cada repintura do progresso. */
        var textoArmado: String? = null
    }
    inner class VHEdicao(val et: EditText) : RecyclerView.ViewHolder(et)
    inner class VHAlteracoes(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> {
                val et = inflater.inflate(R.layout.item_paragrafo_edit, parent, false) as EditText
                VHEdicao(et)
            }
            2 -> {
                val tv = inflater.inflate(R.layout.item_paragrafo, parent, false) as TextView
                VHAlteracoes(tv)
            }
            else -> {
                val tv = inflater.inflate(R.layout.item_paragrafo, parent, false) as TextView
                VHLeitura(tv)
            }
        }
    }

    override fun getItemCount() = when (modo) {
        AdapterModo.ALTERACOES -> maxOf(paragrafosOriginais.size, paragrafos.size)
        else -> paragrafos.size
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val par = paragrafos.getOrNull(position)
        val corTexto = when (tema) {
            TemaLeitura.CLARO  -> Color.parseColor("#333333")
            TemaLeitura.ESCURO -> Color.parseColor("#E0E0E0")
            TemaLeitura.SEPIA  -> Color.parseColor("#3B2A1A")
            TemaLeitura.OLED   -> Color.parseColor("#CCCCCC")
        }
        when (holder) {
            is VHLeitura    -> bindLeitura(holder, par, position, corTexto)
            is VHEdicao     -> bindEdicao(holder, par, position, corTexto)
            is VHAlteracoes -> bindAlteracoes(holder, par, position, corTexto)
        }
    }


    private fun bindLeitura(holder: VHLeitura, par: Paragrafo?, position: Int, corTexto: Int) {
        par ?: return
        val tv = holder.tv

        // Há uma seleção em andamento neste item (ex.: o usuário está escolhendo uma palavra): não
        // reaplica o texto — cada atualização do progresso da leitura guiada rebindava o item e
        // cancelava a seleção. Só o fundo e a fonte são atualizados.
        val selecaoAtiva = tv.hasSelection() && tv.text.toString() == par.texto
        if (!selecaoAtiva) {
            // View reaproveitada pelo RecyclerView: sem reativar, a seleção de texto (textIsSelectable)
            // deixa de responder ao toque longo depois de rolar a lista. Alternar reinicia o estado.
            // Só quando o item passou a mostrar outro parágrafo: repintar o parágrafo em leitura 20x/s
            // e alternar a cada vez cancelava o toque longo antes de ele completar.
            if (holder.textoArmado != par.texto) {
                tv.setTextIsSelectable(false)
                tv.setTextIsSelectable(true)
                holder.textoArmado = par.texto
            }
            tv.text = par.texto
        }
        tv.textSize = tamanhoFonte
        tv.setLineSpacing(0f, espacamento)
        tv.setTextColor(corTexto)
        tv.aplicarTipografiaLeitor(fonteSerifada, typefaceSerifado)

        // Highlight
        val inicio = selecaoInicio
        val fim = selecaoFim
        val estaNoSync  = position == syncAtual
        val estaSelecao = inicio != null && fim != null
                && position >= inicio && position <= fim
        val estaBuscaAtual = position == posicaoResultadoBusca(buscaResultados, buscaIndiceAtual)
        val estaBuscaMatch = buscaResultados.contains(position)

        val corFundo = when {
            estaSelecao    -> COR_SELECAO[tema]
            estaBuscaAtual -> COR_BUSCA_ATUAL[tema]
            estaBuscaMatch -> COR_BUSCA_MATCH[tema]
            estaNoSync     -> COR_SYNC[tema]
            else           -> Color.TRANSPARENT
        } ?: Color.TRANSPARENT
        val destaquesDoParagrafo = destaques[position].orEmpty()

        if (selecaoAtiva) {
            tv.setBackgroundColor(corFundo)   // preserva a seleção do usuário
        } else if (estaNoSync && progressPct > 0f) {
            tv.aplicarTextoComSyncEDestaques(par.texto, corFundo, tema, progressPct, destaquesDoParagrafo)
        } else if (destaquesDoParagrafo.isNotEmpty()) {
            val spannable = android.text.SpannableString(par.texto)
            spannable.aplicarDestaques(destaquesDoParagrafo, par.texto.length)
            tv.text = spannable
            tv.setBackgroundColor(corFundo)
        } else {
            tv.text = par.texto
            tv.setBackgroundColor(corFundo)
        }

        tv.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                val item1 = menu.add(0, MENU_ITEM_EXCLUIR_OCORRENCIAS, 0, R.string.btn_excluir_ocorrencias)
                item1.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

                val item2 = menu.add(0, MENU_ITEM_SELECIONAR_PARAGRAFO, 0, R.string.menu_selecionar_paragrafo)
                item2.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

                val item3 = menu.add(0, MENU_ITEM_DESTACAR, 0, R.string.menu_destacar_trecho)
                item3.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

                val item4 = menu.add(0, MENU_ITEM_DEFINIR, 0, R.string.menu_definir_palavra)
                item4.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

                val item5 = menu.add(0, MENU_ITEM_PRONUNCIA, 0, R.string.menu_pronuncia)
                item5.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                return false
            }

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val start = tv.selectionStart
                val end = tv.selectionEnd
                if (start < 0 || end <= start) return false
                val selectedText = tv.text.subSequence(start, end).toString().trim()
                var tratado = false
                when (item.itemId) {
                    MENU_ITEM_EXCLUIR_OCORRENCIAS -> {
                        if (selectedText.isNotEmpty()) {
                            viewModel.excluirOcorrencias(selectedText)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_blacklist_added),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        mode.finish()
                        tratado = true
                    }
                    MENU_ITEM_SELECIONAR_PARAGRAFO -> {
                        onLongPress(position)
                        com.google.android.material.snackbar.Snackbar
                            .make(
                                recyclerView,
                                activity.getString(R.string.snackbar_selecao_paragrafo),
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            )
                            .show()
                        mode.finish()
                        tratado = true
                    }
                    MENU_ITEM_DESTACAR -> {
                        onDestacarTrecho(position, start, end)
                        mode.finish()
                        tratado = true
                    }
                    MENU_ITEM_DEFINIR -> {
                        if (selectedText.isNotEmpty()) activity.definirPalavra(selectedText)
                        mode.finish()
                        tratado = true
                    }
                    MENU_ITEM_PRONUNCIA -> {
                        if (selectedText.isNotEmpty()) onDefinirPronuncia(selectedText)
                        mode.finish()
                        tratado = true
                    }
                }
                return tratado
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }

        tv.setOnClickListener    { onToque(position) }
    }

    private fun bindEdicao(holder: VHEdicao, par: Paragrafo?, position: Int, corTexto: Int) {
        par ?: return
        val et = holder.et
        // Remove listener anterior para evitar disparo durante setText
        (et.tag as? TextWatcher)?.let { et.removeTextChangedListener(it) }
        et.tag = null
        et.setText(edicoesAtuais[position] ?: par.texto)
        et.textSize = tamanhoFonte
        et.setLineSpacing(0f, espacamento)
        et.setTextColor(corTexto)
        et.aplicarTipografiaLeitor(fonteSerifada, typefaceSerifado)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                edicoesAtuais[position] = s?.toString() ?: par.texto
            }
        }
        et.tag = watcher
        et.addTextChangedListener(watcher)
    }

    private fun bindAlteracoes(holder: VHAlteracoes, par: Paragrafo?, position: Int, corTexto: Int) {
        val tv = holder.tv
        tv.textSize = tamanhoFonte
        tv.setLineSpacing(0f, espacamento)
        tv.setBackgroundColor(Color.TRANSPARENT)
        tv.aplicarTipografiaLeitor(fonteSerifada, typefaceSerifado)

        // Em modo ALTERACOES, `paragrafos` = parágrafos limpos, `paragrafosOriginais` = originais
        // Exibe todos os parágrafos originais: removidos = tachado vermelho, mantidos = cor normal
        val textosLimpos = paragrafos.map { it.texto }.toSet()
        val parOriginal = paragrafosOriginais.getOrNull(position)

        if (parOriginal != null && parOriginal.texto !in textosLimpos) {
            // Parágrafo removido - tachado em vermelho escuro
            val spannable = SpannableString(parOriginal.texto)
            spannable.setSpan(
                StrikethroughSpan(),
                0, spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(0xFFB71C1C.toInt()),
                0, spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tv.text = spannable
        } else {
            tv.text = parOriginal?.texto ?: par?.texto ?: ""
            tv.setTextColor(corTexto)
        }
    }

    companion object {
        private const val FONTE_PADRAO_SP = 16f
        private const val ESPACAMENTO_PADRAO = 1.5f
    }
}

/**
 * Fonte serifada (Literata) se ativada nas prefs de aparência; senão a padrão do sistema.
 * Função de topo (não membro da classe) para não empurrar ParagrafoAdapter acima do
 * limite de funções do detekt.
 */
private fun TextView.aplicarTipografiaLeitor(serifada: Boolean, tipografiaSerifada: android.graphics.Typeface?) {
    typeface = if (serifada) tipografiaSerifada else android.graphics.Typeface.DEFAULT
}

/**
 * "Definir" (T3.6): tenta abrir um app de dicionário instalado via ACTION_PROCESS_TEXT
 * (mesma ação usada pela seleção de texto nativa do Android); se nenhum app resolver,
 * cai para busca no navegador. Zero dependências novas. Função de topo pelo mesmo motivo
 * das outras — não empurrar ParagrafoAdapter acima do limite de funções do detekt.
 */
private fun AppCompatActivity.definirPalavra(texto: String) {
    val intentDicionario = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, texto)
        putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    if (intentDicionario.resolveActivity(packageManager) != null) {
        startActivity(intentDicionario)
        return
    }
    val intentBusca = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).apply {
        putExtra(android.app.SearchManager.QUERY, texto)
    }
    if (intentBusca.resolveActivity(packageManager) != null) {
        startActivity(intentBusca)
    } else {
        Toast.makeText(this, R.string.toast_definir_indisponivel, Toast.LENGTH_SHORT).show()
    }
}

/** Cor do destaque persistente (T3.5) — amarelo translúcido, legível sobre os 4 temas. */
private val COR_DESTAQUE = Color.parseColor("#55FFC107")

/**
 * Aplica um BackgroundColorSpan por trecho destacado salvo. Função de topo (não membro da
 * classe) pelo mesmo motivo de [aplicarTipografiaLeitor] — não estourar TooManyFunctions.
 */
private fun android.text.SpannableString.aplicarDestaques(destaques: List<IntRange>, maxLen: Int) {
    destaques.forEach { range ->
        val start = range.first.coerceIn(0, maxLen)
        val end = (range.last + 1).coerceIn(start, maxLen)
        if (end > start) {
            setSpan(
                android.text.style.BackgroundColorSpan(COR_DESTAQUE),
                start, end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}

/**
 * Texto do parágrafo em sincronia da leitura guiada (com overlay mostrando o quanto já foi
 * lido) + destaques salvos, se houver. Função de topo — extraída de [bindLeitura] pra não
 * estourar o limite de tamanho de método do detekt.
 */
private fun TextView.aplicarTextoComSyncEDestaques(
    texto: String,
    corFundo: Int,
    tema: TemaLeitura,
    progressPct: Float,
    destaquesDoParagrafo: List<IntRange>
) {
    val corDestaqueSync = when (tema) {
        TemaLeitura.CLARO  -> Color.parseColor("#BBA6FF")
        TemaLeitura.ESCURO -> Color.parseColor("#6A4CA0")
        TemaLeitura.SEPIA  -> Color.parseColor("#D8B880")
        TemaLeitura.OLED   -> Color.parseColor("#6A4CA0")
    }
    val spannable = android.text.SpannableString(texto)
    spannable.setSpan(
        android.text.style.BackgroundColorSpan(corFundo),
        0, texto.length,
        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    )

    var prog = (texto.length * progressPct).toInt().coerceIn(0, texto.length)
    while (prog < texto.length && !texto[prog].isWhitespace()) {
        prog++
    }
    if (prog > 0) {
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(corDestaqueSync),
            0, prog,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    spannable.aplicarDestaques(destaquesDoParagrafo, texto.length)
    text = spannable
    setBackgroundColor(Color.TRANSPARENT)
}
