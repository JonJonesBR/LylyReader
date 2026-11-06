package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.AlinhamentoTexto
import com.jonjonesbr.audiobookgen.domain.FonteLeitura
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TipografiaLeitor
import com.jonjonesbr.audiobookgen.domain.espacoLetrasEm
import com.jonjonesbr.audiobookgen.domain.espacoParagrafosDp
import com.jonjonesbr.audiobookgen.domain.espacoParagrafosEdicaoDp
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import com.jonjonesbr.audiobookgen.util.urlDeBuscaDoSignificado
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

enum class AdapterModo { LEITURA, EDICAO, ALTERACOES }

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
// Claro e Sépia escurecidos (a11y-4): o destaque do parágrafo narrado tinha 1,12:1 e 1,20:1 contra o fundo.
private val COR_SYNC = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#DAD2F5"),
    TemaLeitura.ESCURO to Color.parseColor("#2A2040"),
    TemaLeitura.SEPIA  to Color.parseColor("#DFC99C"),
    TemaLeitura.OLED   to Color.parseColor("#2A2040"),
)

// Barra vertical na borda inicial do parágrafo narrado (a11y-4): sinal além da cor, com contraste >= 3:1
// contra o fundo do tema e contra o destaque de sincronia (WCAG 1.4.11).
private val COR_BARRA_SYNC = mapOf(
    TemaLeitura.CLARO  to Color.parseColor("#6A4CA0"),
    TemaLeitura.ESCURO to Color.parseColor("#B39DDB"),
    TemaLeitura.SEPIA  to Color.parseColor("#6B4423"),
    TemaLeitura.OLED   to Color.parseColor("#B39DDB"),
)
private const val LARGURA_BARRA_SYNC_DP = 4f

/**
 * Adapter do RecyclerView de parágrafos da leitura (modos leitura/edição/comparação de
 * alterações). Extraído de ReaderActivity (Fase 1) — segue o mesmo padrão de arquivo dedicado já
 * usado por AudiobookAdapter/QueueAdapter. Era uma `inner class`; os poucos pontos que dependiam
 * da Activity (getString, Toast, viewModel, o RecyclerView para o Snackbar) viraram parâmetros
 * de construtor.
 */
@Suppress("LongParameterList") // callbacks do adapter, só lambdas: a contagem cresceu com o menu da seleção (rb-30)
class ParagrafoAdapter(
    private val activity: AppCompatActivity,
    private val recyclerView: RecyclerViewParagrafos,
    // Barra própria das ações da seleção de texto (LinearLayout sobreposto ao topo da lista, rb-38).
    barraSelecaoTexto: View,
    private val viewModel: ReaderViewModel,
    private val onLongPress: (Int) -> Unit,
    private val onToque: (Int) -> Unit,
    // T3.5 — destacar um trecho selecionado do parágrafo (posição, início, fim em char offset)
    private val onDestacarTrecho: (Int, Int, Int) -> Unit,
    // Dicionário de pronúncia — palavra selecionada
    private val onDefinirPronuncia: (String) -> Unit = {},
    // "Gerar áudio" do trecho marcado no menu da seleção (rb-30)
    private val onGerarAudioDoTrecho: (String) -> Unit = {},
    // "Ler sem áudio": sem Gerar áudio, Selecionar vários e Pronúncia (que toca o texto) no menu da seleção
    private val comAudio: Boolean = true,
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
    private var tipografia: TipografiaLeitor = TipografiaLeitor()
    private var alinhamento: AlinhamentoTexto = AlinhamentoTexto.ESQUERDA
    var modo: AdapterModo = AdapterModo.LEITURA
        private set

    // Menu da seleção nativa aberto agora (rb-38): a barra do Leitor executa a ação por ele.
    private var menuAberto: MenuDaSelecaoDeTexto? = null
    // Último menu que abriu a barra: outro menu é outro parágrafo, e a barra começa uma sessão nova (rb-54).
    private var ultimoMenuAberto: MenuDaSelecaoDeTexto? = null
    private val barraDaSelecao: BarraDaSelecaoDeTexto =
        BarraDaSelecaoDeTexto(barraSelecaoTexto, recyclerView, comAudio) { acao ->
            val menu = menuAberto
            if (menu != null) menu.executarPelaBarra(acao) else selecaoSemMenu.agirPelaBarra(acao)
        }
    // A barra própria, para ver se o foco foi para um dos botões dela (rb-57).
    private val barraDeTexto: View = barraSelecaoTexto
    // Barra da seleção viva quando o menu do sistema não está aberto (rb-54). Recebe as ações pela lambda: o
    // acoesDoMenu é declarado depois, e só é lido quando a barra age.
    private val selecaoSemMenu: SelecaoSemMenu = SelecaoSemMenu(activity, recyclerView, barraDaSelecao) { acoesDoMenu }
    // Repinturas de sincronia seguradas enquanto há dedo na lista ou seleção aberta (rb-38).
    private val repinturas = RepinturasPendentes()

    init {
        // Depois de a lista de parágrafos e as repinturas existirem: aplica o que ficou seguro ao soltar o dedo.
        recyclerView.aoLiberarTexto = {
            repinturas.liberar().filter { it in paragrafos.indices }.forEach { notifyItemChanged(it) }
        }
        recyclerView.aoConferirSelecao = { selecaoSemMenu.conferir() }
    }

    private val acoesDoMenu: AcoesDaSelecaoDeTexto = AcoesDaSelecaoDeTexto(
        gerarAudio = onGerarAudioDoTrecho,
        selecionarVarios = { posicao ->
            onLongPress(posicao)
            com.google.android.material.snackbar.Snackbar.make(
                recyclerView,
                activity.getString(R.string.snackbar_selecao_paragrafo),
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
        },
        destacar = onDestacarTrecho,
        pronuncia = onDefinirPronuncia,
        definir = { activity.definirPalavra(it) },
        excluirOcorrencias = { trecho ->
            viewModel.excluirOcorrencias(trecho)
            Toast.makeText(activity, activity.getString(R.string.toast_blacklist_added), Toast.LENGTH_SHORT).show()
        },
        aoAbrir = { menu ->
            menuAberto = menu
            recyclerView.abriuSelecao()
            // Seleção nova substitui o trecho guardado (rb-58).
            if (recyclerView.guardada.ativa) selecaoSemMenu.limparGuardada()
            // Outro parágrafo (outro menu) é sessão nova da barra: a descida se ajusta ao trecho novo.
            val outraSelecao = menu !== ultimoMenuAberto
            ultimoMenuAberto = menu
            barraDaSelecao.mostrar(
                outraSelecao = outraSelecao,
                // rb-50: grava a seleção que a descida acabou de usar, para o preparo do UP não a refazer à toa.
                depois = {
                    menu.inicioDaUltimaDescida = menu.inicioDaSelecao
                    menu.fimDaUltimaDescida = menu.fimDaSelecao
                },
                linhaDaSelecao = { menu.linhaDoInicioDaSelecaoNaTela() },
            )
        },
        aoFechar = { menu ->
            if (menuAberto === menu) menuAberto = null
            // Não esconde a barra aqui: o fechouSelecao agenda a conferência, que a esconde ou a mantém se a seleção
            // continua viva sem o menu (rb-54).
            recyclerView.fechouSelecao()
        },
        // rb-50: ao soltar a alça, a descida se refaz para a linha nova do início. Depois pede a barra flutuante de
        // novo (invalidateContentRect, sem onPrepare, então não há laço).
        aoSoltarAlca = { menu ->
            if (menuAberto === menu) {
                barraDaSelecao.reajustar({ menu.linhaDoInicioDaSelecaoNaTela() }) {
                    menu.inicioDaUltimaDescida = menu.inicioDaSelecao
                    menu.fimDaUltimaDescida = menu.fimDaSelecao
                    menu.modo?.invalidateContentRect()
                }
            }
        },
    )

    // Carregados uma única vez (não a cada bind) — fontes opcionais da leitura.
    private val typefaceSerifado: android.graphics.Typeface? by lazy {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.literata_regular)
        }.getOrNull()
    }
    private val typefaceDislexia: android.graphics.Typeface? by lazy {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.opendyslexic_regular)
        }.getOrNull()
    }

    // Para o modo ALTERACOES: parágrafos originais pré-computados
    private var paragrafosOriginais: List<Paragrafo> = emptyList()

    // Textos dos parágrafos limpos (modo ALTERACOES): calculado no primeiro bind depois de cada troca de lista,
    // e não a cada item exibido (dev-9).
    private var textosLimpos: Set<String>? = null

    // Para o modo EDICAO: rastrear edições feitas pelo usuário
    private val edicoesAtuais = mutableMapOf<Int, String>()

    // T4.1 — Busca
    var buscaResultados: List<Int> = emptyList()
    var buscaIndiceAtual: Int = -1
    // Trechos achados por parágrafo (posições de caractere), para pintar a palavra em si
    var buscaTrechos: Map<Int, List<IntRange>> = emptyMap()

    // T3.5 — Destaques: índice do parágrafo -> trechos destacados (char offsets locais ao texto)
    var destaques: Map<Int, List<IntRange>> = emptyMap()

    fun consumirEdicoes(): Map<Int, String> =
        edicoesAtuais.toMap().also { edicoesAtuais.clear() }

    // Lista de parâmetros já era longa antes (10, baselined). A fonte e os espaços entram agrupados em
    // TipografiaLeitor, então a contagem não cresce; o @Suppress segue valendo para o detekt.
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
        tipografia: TipografiaLeitor = this.tipografia,
        alinhamento: AlinhamentoTexto = this.alinhamento
    ) {
        val mudouModo = this.modo != modo
        val mudouLista = this.paragrafos !== paragrafos || mudouModo
        val mudouAparencia = this.tamanhoFonte != tamanhoFonte ||
            this.espacamento != espacamento ||
            this.tema != tema ||
            this.selecaoInicio != selecaoInicio ||
            this.selecaoFim != selecaoFim ||
            this.paragrafosOriginais !== paragrafosOriginais ||
            this.tipografia != tipografia ||
            this.alinhamento != alinhamento
        val syncAntigo = this.syncAtual
        if (mudouLista) textosLimpos = null
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
        this.tipografia = tipografia
        this.alinhamento = alinhamento
        when {
            mudouLista -> {
                repinturas.descartar()
                notifyDataSetChanged()
                // Lista nova: o trecho guardado era de outro texto (rb-58). Depois do notify, por causa do detach.
                recyclerView.guardada.limpar()
                recyclerView.liberarDepois()
            }
            mudouAparencia -> {
                repinturas.descartar()
                notifyItemRangeChanged(0, paragrafos.size)
            }
            modo == AdapterModo.LEITURA -> {
                // Atualização só de progresso/sync (leitura guiada, 20fps):
                // repinta apenas o parágrafo atual e o anterior — repintar a lista
                // inteira a cada tick era a causa do flicker (#3).
                // Sem parágrafo de sync (guiada encerrada ou sync do audiobook desligado; rolar com o dedo não zera o
                // sync, só liga o modo manual da guiada) não há nada a repintar: o fallback antigo repintava a LISTA
                // INTEIRA a cada tick, e cada repintura reinicia o estado de seleção dos TextViews — a causa de não
                // conseguir selecionar palavras depois de rolar.
                // Com dedo na lista ou seleção aberta, a repintura espera (ver RecyclerViewParagrafos.textoEmUso).
                val alvos = linkedSetOf(syncAntigo, syncAtual).filter { it in paragrafos.indices }
                repinturas.pedir(alvos, segurar = recyclerView.textoEmUso).forEach { notifyItemChanged(it) }
            }
            // Edição: sem mudança de lista nem de aparência não há o que repintar. Repintar refazia o
            // setText do campo em que o usuário digitava (cursor no início, palavra em composição cortada).
            modo == AdapterModo.EDICAO -> Unit
            else -> notifyItemRangeChanged(0, paragrafos.size)
        }
    }

    override fun getItemViewType(position: Int) = when (modo) {
        AdapterModo.LEITURA    -> TIPO_LEITURA
        AdapterModo.EDICAO     -> TIPO_EDICAO
        AdapterModo.ALTERACOES -> TIPO_ALTERACOES
    }

    /** O itemView é o próprio tv (o modo páginas é virtual, por cima da lista vertical; ver PaginacaoDoLeitor). */
    inner class VHLeitura(itemView: View, val tv: TextView) : RecyclerView.ViewHolder(itemView) {
        /** Texto para o qual a seleção já foi (re)armada; evita reiniciá-la a cada repintura do progresso. */
        var textoArmado: String? = null

        init {
            // Menu e clique são ligados uma vez por item, e não a cada bind: o narrado é repintado ~20x/s.
            tv.customSelectionActionModeCallback =
                MenuDaSelecaoDeTexto(tv, { bindingAdapterPosition }, acoesDoMenu)
            // Toque curto lê dali já no 1º toque. Toque longo que não selecionou e toque que só fecha a seleção
            // não leem (rb-38, rb-42).
            tv.ligarToqueNoParagrafo(selecaoAberta = { recyclerView.selecaoDeTextoAberta }) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onToque(pos)
            }
            // Seleção sem foco é órfã (rb-54): desfaz quando o parágrafo perde o foco, se o menu não está aberto. Por
            // post, porque o Editor pode fechar o modo dentro da troca de foco. Com o menu aberto, a seleção é dele.
            tv.setOnFocusChangeListener { _, temFoco ->
                if (!temFoco) tv.post {
                    // Foco indo para um botão da barra (teclado, D-pad, rb-57): a seleção fica para o clique.
                    val novoFoco = tv.rootView.findFocus()
                    val foiParaABarra = novoFoco != null && (novoFoco.estaDentroDe(barraDeTexto) ||
                        novoFoco.estaDentroDe(tv.rootView.findViewById<View>(R.id.layoutBarraSelecao)))
                    if (!tv.isFocused && !foiParaABarra && tv.desfazerSelecaoSemMenu()) recyclerView.liberarDepois()
                }
            }
        }
    }
    inner class VHEdicao(val et: EditText) : RecyclerView.ViewHolder(et)

    /** O itemView é o próprio tv. */
    inner class VHAlteracoes(itemView: View, val tv: TextView) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TIPO_EDICAO -> {
                val et = inflater.inflate(R.layout.item_paragrafo_edit, parent, false) as EditText
                VHEdicao(et)
            }
            TIPO_ALTERACOES -> {
                val tv = inflater.inflate(R.layout.item_paragrafo, parent, false) as TextView
                VHAlteracoes(tv, tv)
            }
            else -> {
                val tv = inflater.inflate(R.layout.item_paragrafo, parent, false) as TextView
                VHLeitura(tv, tv)
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


    /**
     * rb-53: com o dedo sobre o item e o texto já na tela, a repintura não refaz o texto. A posição fica segurada e é
     * repintada quando o toque termina (RecyclerViewParagrafos.aoLiberarTexto). Devolve se o bind parou aqui.
     */
    private fun segurarTextoDoBind(holder: VHLeitura, par: Paragrafo, position: Int, selecaoAtiva: Boolean): Boolean {
        val manter = manterTextoNoBind(
            textoJaNaTela = holder.textoArmado == par.texto,
            sobODedo = recyclerView.sobODedo(holder.itemView),
            selecaoAtiva = selecaoAtiva,
        )
        if (manter) repinturas.pedir(listOf(position), segurar = true)
        return manter
    }

    private fun bindLeitura(holder: VHLeitura, par: Paragrafo?, position: Int, corTexto: Int) {
        par ?: return
        val tv = holder.tv

        // Há uma seleção em andamento neste item (ex.: o usuário está escolhendo uma palavra): não
        // reaplica o texto — cada atualização do progresso da leitura guiada rebindava o item e
        // cancelava a seleção. Só o fundo e a fonte são atualizados.
        // Seleção sem foco é órfã (rb-54): não conta, e o setText abaixo a desfaz.
        val selecaoAtiva = tv.hasSelection() && tv.isFocused && tv.text.toString() == par.texto
        // rb-53: a repintura de sincronia que chega com o dedo já sobre o parágrafo fica para depois do toque.
        if (segurarTextoDoBind(holder, par, position, selecaoAtiva)) return
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
        tv.aplicarTipografiaLeitor(tipografia, typefaceSerifado, typefaceDislexia, alinhamento)

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
        val trechosBusca = buscaTrechos[position].orEmpty()
        val corTrechoBusca = if (estaBuscaAtual) COR_TRECHO_BUSCA_ATUAL else COR_TRECHO_BUSCA

        if (selecaoAtiva) {
            tv.pintarFundoParagrafo(corFundo, estaNoSync, tema)   // preserva a seleção do usuário
        } else if (estaNoSync && progressPct > 0f) {
            tv.aplicarTextoComSyncEDestaques(par.texto, corFundo, tema, progressPct, destaquesDoParagrafo)
        } else if (destaquesDoParagrafo.isNotEmpty() || trechosBusca.isNotEmpty()) {
            val spannable = android.text.SpannableString(par.texto)
            spannable.aplicarDestaques(destaquesDoParagrafo, par.texto.length)
            spannable.aplicarDestaques(trechosBusca, par.texto.length, corTrechoBusca)
            tv.text = spannable
            tv.pintarFundoParagrafo(corFundo, estaNoSync, tema)
        } else {
            tv.text = par.texto
            tv.pintarFundoParagrafo(corFundo, estaNoSync, tema)
        }
        // rb-58: o trecho guardado volta pintado depois do setText dos ramos acima, que tiram os spans antigos. Com a
        // seleção do usuário o texto não foi trocado, e os spans ficam como estão.
        if (!selecaoAtiva) tv.pintarTrechoGuardado(recyclerView.guardada.trechoNoParagrafo(position, par.texto))
    }

    private fun bindEdicao(holder: VHEdicao, par: Paragrafo?, position: Int, corTexto: Int) {
        par ?: return
        val et = holder.et
        // Remove listener anterior para evitar disparo durante setText
        (et.tag as? TextWatcher)?.let { et.removeTextChangedListener(it) }
        et.tag = null
        // Só troca o texto se o campo mostra outra coisa (item reaproveitado): com o mesmo conteúdo, setText
        // jogava o cursor para o início e cortava a palavra que o teclado estava compondo.
        val texto = edicoesAtuais[position] ?: par.texto
        if (et.text.toString() != texto) et.setText(texto)
        et.textSize = tamanhoFonte
        et.setLineSpacing(0f, espacamento)
        et.setTextColor(corTexto)
        // Edição sempre alinhada à esquerda: com o texto justificado, o Android posiciona o cursor e o toque
        // sem contar o espaço esticado entre as palavras, e o cursor parava antes da palavra editada.
        val alinhamentoEdicao =
            if (alinhamento == AlinhamentoTexto.JUSTIFICADO) AlinhamentoTexto.ESQUERDA else alinhamento
        et.aplicarTipografiaLeitor(tipografia, typefaceSerifado, typefaceDislexia, alinhamentoEdicao, edicao = true)
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
        // Em modo ALTERACOES, `paragrafos` = parágrafos limpos, `paragrafosOriginais` = originais
        // Exibe todos os parágrafos originais: removidos = tachado vermelho, mantidos = cor normal
        val parOriginal = paragrafosOriginais.getOrNull(position)
        tv.textSize = tamanhoFonte
        tv.setLineSpacing(0f, espacamento)
        tv.setBackgroundColor(Color.TRANSPARENT)
        tv.aplicarTipografiaLeitor(tipografia, typefaceSerifado, typefaceDislexia, alinhamento)

        val conjuntoLimpos = textosLimpos
            ?: paragrafos.mapTo(HashSet<String>()) { it.texto }.also { textosLimpos = it }

        if (parOriginal != null && parOriginal.texto !in conjuntoLimpos) {
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

        // Tipos de view do adapter.
        private const val TIPO_LEITURA = 0
        private const val TIPO_EDICAO = 1
        private const val TIPO_ALTERACOES = 2
    }
}

/**
 * Tipografia do texto no leitor: a fonte escolhida (se a opcional não carregou, a padrão do sistema), o espaço
 * entre letras e o espaço entre parágrafos (padding vertical; o horizontal fica como está, pois a barra do narrado
 * é desenhada nele). Função de topo (não membro da classe) para não empurrar ParagrafoAdapter acima do limite de
 * funções do detekt. Na edição ([edicao] = true), o padding vertical segue a tabela do campo de edição.
 */
private fun TextView.aplicarTipografiaLeitor(
    tipografia: TipografiaLeitor,
    tipografiaSerifada: android.graphics.Typeface?,
    tipografiaDislexia: android.graphics.Typeface?,
    alinhamento: AlinhamentoTexto,
    edicao: Boolean = false
) {
    val fonte = when (tipografia.fonte) {
        FonteLeitura.PADRAO -> android.graphics.Typeface.DEFAULT
        FonteLeitura.SERIFADA -> tipografiaSerifada ?: android.graphics.Typeface.DEFAULT
        FonteLeitura.DISLEXIA -> tipografiaDislexia ?: android.graphics.Typeface.DEFAULT
    }
    // Só quando muda: o setter refaz o layout mesmo com o mesmo valor, e esta função roda a cada repintura.
    if (typeface != fonte) typeface = fonte
    val espacoLetras = espacoLetrasEm(tipografia.espacoLetras)
    if (letterSpacing != espacoLetras) letterSpacing = espacoLetras
    val dpVertical = if (edicao) {
        espacoParagrafosEdicaoDp(tipografia.espacoParagrafos)
    } else {
        espacoParagrafosDp(tipografia.espacoParagrafos)
    }
    val paddingVertical = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dpVertical.toFloat(),
        resources.displayMetrics
    ).toInt()
    if (paddingTop != paddingVertical || paddingBottom != paddingVertical) {
        setPaddingRelative(paddingStart, paddingVertical, paddingEnd, paddingVertical)
    }
    // Justificado distribui o espaço entre as palavras; a última linha do parágrafo fica à esquerda.
    val modo = if (alinhamento == AlinhamentoTexto.JUSTIFICADO) {
        android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    } else {
        android.text.Layout.JUSTIFICATION_MODE_NONE
    }
    // Só quando muda: o setter descarta o layout mesmo com o mesmo valor, e esta função roda a cada repintura. Sem
    // layout, o Android encerra a seleção aberta (e um toque nesse instante derrubava o app).
    if (justificationMode != modo) justificationMode = modo
    textAlignment = when (alinhamento) {
        AlinhamentoTexto.CENTRO -> View.TEXT_ALIGNMENT_CENTER
        AlinhamentoTexto.DIREITA -> View.TEXT_ALIGNMENT_VIEW_END
        AlinhamentoTexto.JUSTIFICADO, AlinhamentoTexto.ESQUERDA -> View.TEXT_ALIGNMENT_VIEW_START
    }
}

/**
 * "Definir" (T3.6): abre direto a busca na web pelo significado do termo ("significado de X"), sem o seletor de
 * apps. ACTION_PROCESS_TEXT saiu (rb-44): sem app de dicionário, o seletor mostrava apps de IA. Tenta a busca do
 * sistema (ACTION_WEB_SEARCH); sem quem trate, abre o Google no navegador; se também falhar, avisa.
 * Sem resolveActivity (no Android 11+ depende de <queries>): o try/catch de ActivityNotFoundException cobre o caso.
 * Função de topo pelo mesmo motivo das outras — não empurrar ParagrafoAdapter acima do limite de funções do detekt.
 */
private fun AppCompatActivity.definirPalavra(texto: String) {
    val consulta = getString(R.string.definir_consulta, texto)
    val intentBusca = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).apply {
        putExtra(android.app.SearchManager.QUERY, consulta)
    }
    val intentNavegador = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(urlDeBuscaDoSignificado(consulta)),
    )
    for (intent in listOf(intentBusca, intentNavegador)) {
        try {
            startActivity(intent)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // Nenhum app trata esta intenção: tenta a próxima.
        }
    }
    Toast.makeText(this, R.string.toast_definir_indisponivel, Toast.LENGTH_SHORT).show()
}

/** Cor do destaque persistente (T3.5) — amarelo translúcido, legível sobre os 4 temas. */
private val COR_DESTAQUE = Color.parseColor("#55FFC107")

// Palavra achada pela busca: laranja forte no resultado atual, amarelo nos demais. Cores opacas
// com texto escuro por cima não funcionariam nos temas escuros; a transparência mantém o texto legível.
private val COR_TRECHO_BUSCA_ATUAL = Color.parseColor("#CCFF9800")
private val COR_TRECHO_BUSCA = Color.parseColor("#88FFEB3B")

/**
 * Aplica um BackgroundColorSpan por trecho destacado salvo. Função de topo (não membro da
 * classe) pelo mesmo motivo de [aplicarTipografiaLeitor] — não estourar TooManyFunctions.
 */
private fun android.text.SpannableString.aplicarDestaques(
    destaques: List<IntRange>,
    maxLen: Int,
    cor: Int = COR_DESTAQUE
) {
    destaques.forEach { range ->
        val start = range.first.coerceIn(0, maxLen)
        val end = (range.last + 1).coerceIn(start, maxLen)
        if (end > start) {
            setSpan(
                android.text.style.BackgroundColorSpan(cor),
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
    val spannable = android.text.SpannableString(texto)

    var prog = (texto.length * progressPct).toInt().coerceIn(0, texto.length)
    while (prog < texto.length && !texto[prog].isWhitespace()) {
        prog++
    }
    if (prog > 0) {
        // Trecho já lido em cor translúcida (rb-56): a seleção nativa aparece por baixo do span.
        spannable.setSpan(
            android.text.style.BackgroundColorSpan(corDoTrechoLido(tema)),
            0, prog,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    spannable.aplicarDestaques(destaquesDoParagrafo, texto.length)
    text = spannable
    // A cor do parágrafo narrado vai para o fundo da view (e não para um span no texto todo): o fundo fica embaixo
    // do desenho da seleção, que assim aparece em todo o parágrafo. A barra do narrado vem junto.
    pintarFundoParagrafo(corFundo, narrado = true, tema = tema)
}

/**
 * Fundo do parágrafo: a cor de destaque e, se ele é o narrado, uma barra de [LARGURA_BARRA_SYNC_DP] na borda inicial
 * (espelhada em RTL). A barra é uma camada do próprio fundo, então não muda padding nem margem e o texto não pula.
 * Sem [narrado], o fundo volta a ser só a cor, o que tira a barra de um item reaproveitado. Função de topo pelo
 * mesmo motivo das outras — não empurrar ParagrafoAdapter acima do limite de funções do detekt.
 */
private fun TextView.pintarFundoParagrafo(corFundo: Int, narrado: Boolean, tema: TemaLeitura) {
    if (!narrado) {
        setBackgroundColor(corFundo)
        return
    }
    val corBarra = COR_BARRA_SYNC[tema] ?: Color.TRANSPARENT
    // A leitura guiada repinta o narrado cerca de 20 vezes por segundo: reaproveita a camada já criada por esta
    // função em vez de montar um LayerDrawable novo a cada bind.
    val camadasAtuais = background as? LayerDrawable
    val fundoAtual = camadasAtuais?.getDrawable(0) as? ColorDrawable
    val barraAtual = camadasAtuais?.getDrawable(1) as? ColorDrawable
    if (fundoAtual != null && barraAtual != null) {
        fundoAtual.setColor(corFundo)
        barraAtual.setColor(corBarra)
        return
    }
    val camadaFundo = ColorDrawable(corFundo)
    val camadaBarra = ColorDrawable(corBarra)
    val camadas = LayerDrawable(arrayOf<Drawable>(camadaFundo, camadaBarra))
    camadas.setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
    camadas.setLayerWidth(
        1,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LARGURA_BARRA_SYNC_DP, resources.displayMetrics).toInt()
    )
    // Reforço: a View já propaga a direção ao background, mas a camada nova recebe a direção já agora.
    camadas.setLayoutDirection(layoutDirection)
    background = camadas
}

/** Se a view é [pai] ou está dentro dele. Para ver se o foco foi para a barra da seleção (rb-57). */
internal fun View.estaDentroDe(pai: View?): Boolean =
    pai != null && generateSequence(this) { it.parent as? View }.any { it === pai }

/** Marcador do trecho guardado (rb-58): um BackgroundColorSpan à parte, para tirar só ele sem mexer no resto. */
internal class SpanDoTrechoGuardado(cor: Int) : BackgroundColorSpan(cor)

/**
 * Pinta o trecho guardado do parágrafo, ou tira o marcador com nulo (rb-58). Só mexe nos spans do texto: não troca o
 * texto e não toca no justificado (ver as armadilhas do Leitor). Função de topo, como as outras da view do parágrafo.
 */
internal fun TextView.pintarTrechoGuardado(trecho: IntRange?) {
    val texto = text as? Spannable ?: return
    val marcados = texto.getSpans(0, texto.length, SpanDoTrechoGuardado::class.java)
    if (trecho == null && marcados.isEmpty()) return
    marcados.forEach { texto.removeSpan(it) }
    if (trecho != null) {
        val marcador = SpanDoTrechoGuardado(highlightColor)
        texto.setSpan(marcador, trecho.first, trecho.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    invalidate()
}
