package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.util.abrirExpandida
import com.jonjonesbr.audiobookgen.util.minutosRestantesCapitulo
import com.jonjonesbr.audiobookgen.util.progressoLivroPct

/** Alfa da seta quando não há capítulo para onde ir. */
private const val ALFA_SETA_DESABILITADA = 0.3f
/** Quantos capítulos ficam à vista acima do atual quando o painel abre. */
private const val CAPITULOS_ACIMA_DO_ATUAL = 2
/** Acima desse número de capítulos o painel mostra o campo de busca. */
private const val MIN_CAPITULOS_PARA_BUSCA = 12
/** Marca de "fim do livro na tela" para não refazer o texto do progresso a cada rolagem parada. */
private const val PROGRESSO_CHAVE_FIM = -2
private const val CEM_POR_CENTO = 100

/** Percentual lido do livro e minutos restantes no capítulo, como mostrados na barra de progresso. */
internal data class ProgressoNaBarra(val percentual: Int, val minutos: Int)

/**
 * Progresso mostrado na barra: [indiceRef] é o parágrafo de referência; [noFim] (fim do livro na tela, sem leitura
 * em andamento) fixa 100% e zero minutos. Mesmas contas que estavam em atualizarProgressoLeitura.
 */
internal fun progressoNaBarra(textoAtivo: TextoExtraido, indiceRef: Int, noFim: Boolean): ProgressoNaBarra {
    val pct = if (noFim) 1f else progressoLivroPct(textoAtivo.paragrafos, indiceRef, textoAtivo.totalChars)
    val minutos = if (noFim) 0 else minutosRestantesCapitulo(textoAtivo.paragrafos, indiceRef, textoAtivo.capitulos)
    return ProgressoNaBarra(percentual = (pct * CEM_POR_CENTO).toInt(), minutos = minutos)
}

/**
 * Capítulo em que o painel abre: o da barra, se existir neste texto ([total] capítulos); senão o ativo do ViewModel.
 */
internal fun capituloInicialDoPainel(naBarra: Int, total: Int, ativo: Int): Int =
    if (naBarra in 0 until total) naBarra else ativo

/**
 * Barra do capítulo atual no leitor ("3/42 · Título", com setas), a área clicável que abre o painel de capítulos
 * e o progresso de leitura. Extraído de [ReaderActivity] (rb-7). Padrão do projeto: activity + viewModel + views
 * e callbacks no construtor, sem acessar campos da Activity.
 */
class ReaderCapitulosController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val views: Views,
    private val capitulosSelecionados: MutableSet<Int>,
    private val somenteLeitura: Boolean,
    private val callbacks: Callbacks,
) {
    data class Views(
        val recyclerView: RecyclerView,
        val barra: View,
        val area: View,
        val tvCapitulo: TextView,
        val ivAbrir: View,
        val anterior: ImageButton,
        val proximo: ImageButton,
        val tvProgresso: TextView,
    )

    data class Callbacks(
        val obterParagrafoAtual: () -> Int?,
        val fimDoLivroNaTela: () -> Boolean,
        val obterBarraGuiada: () -> GuidedReadingBarController?,
        val obterIndiceGuiada: () -> Int,
        val atualizarIconeBookmark: (Int?) -> Unit,
        val aoMudarSelecao: () -> Unit,
    )

    /** Painel de capítulos aberto agora: a dica do leitor não aparece por cima dele. */
    var painelAberto = false
        private set

    /** Capítulo da posição de leitura mostrado na barra (-1 = ainda não calculado). */
    private var capituloNaBarra = -1
    private var textoDaBarra: String? = null
    private var progressoIndiceAplicado = -1

    private val appPrefs by lazy { AppPrefs(activity) }

    /** Liga as setas e a área clicável. Chamado uma vez, em vincularViews (depois de atribuir o recyclerView). */
    fun configurar() {
        views.anterior.setOnClickListener { irParaCapitulo(capituloNaBarra - 1) }
        views.proximo.setOnClickListener { irParaCapitulo(capituloNaBarra + 1) }
        views.area.setOnClickListener { mostrarPainel() }
    }

    /**
     * Barra do capítulo atual (substitui a fila de chips): "3/42 · Título", com setas para o anterior e o
     * próximo. O capítulo vem da posição de leitura (parágrafo visível ou o da leitura guiada).
     */
    fun atualizarBarra(texto: TextoExtraido) {
        val capitulos = texto.capitulos
        val temCapitulos = capitulos.isNotEmpty()
        views.barra.visibility =
            if (temCapitulos || views.tvProgresso.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        views.tvCapitulo.visibility = if (temCapitulos) View.VISIBLE else View.GONE
        views.ivAbrir.visibility = views.tvCapitulo.visibility
        views.anterior.visibility = if (temCapitulos) View.VISIBLE else View.INVISIBLE
        views.proximo.visibility = views.anterior.visibility
        if (!temCapitulos) {
            // Sem capítulos não há rótulo: a descrição do livro anterior não pode ficar na área.
            textoDaBarra = null
            atualizarDescricaoArea()
            return
        }

        val paragrafo = callbacks.obterParagrafoAtual() ?: 0
        val atual = NavegacaoCapitulos.indiceDoCapitulo(capitulos, paragrafo).coerceAtLeast(0)
        val rotulo = activity.getString(
            R.string.capitulo_barra, atual + 1, capitulos.size,
            NavegacaoCapitulos.tituloLimpo(capitulos[atual].titulo)
        )
        if (rotulo != textoDaBarra) {
            textoDaBarra = rotulo
            views.tvCapitulo.text = rotulo
            atualizarDescricaoArea()
        }
        if (atual != capituloNaBarra) {
            capituloNaBarra = atual
            views.anterior.isEnabled = atual > 0
            views.anterior.alpha = if (atual > 0) 1f else ALFA_SETA_DESABILITADA
            views.proximo.isEnabled = atual < capitulos.lastIndex
            views.proximo.alpha = if (atual < capitulos.lastIndex) 1f else ALFA_SETA_DESABILITADA
            // "Converter este capítulo" e a lista de capítulos usam o capítulo da posição de leitura.
            if (viewModel.uiState.value.capituloAtivo != atual) viewModel.navegarParaCapitulo(atual)
        }
    }

    /**
     * Descrição da área clicável do capítulo (a11y-7). O pai esconde o texto dos filhos, então o progresso
     * ("42% · ~8 min restantes") entra aqui quando está visível. Só atribui quando muda: roda a cada rolagem.
     */
    private fun atualizarDescricaoArea() {
        val rotulo = textoDaBarra
        val descricao = when {
            rotulo == null -> null
            views.tvProgresso.visibility == View.VISIBLE ->
                activity.getString(R.string.cd_abrir_capitulos_progresso, rotulo, views.tvProgresso.text)
            else -> activity.getString(R.string.cd_abrir_capitulos, rotulo)
        }
        if (views.area.contentDescription?.toString() != descricao) views.area.contentDescription = descricao
    }

    /** Vai ao capítulo [indice]: na leitura guiada passa a ler dali; fora dela, rola o texto até ele. */
    fun irParaCapitulo(indice: Int) {
        val capitulo = viewModel.textoEfetivo()?.capitulos?.getOrNull(indice) ?: return
        viewModel.navegarParaCapitulo(indice)
        val guiada = callbacks.obterBarraGuiada()
        if (guiada != null && callbacks.obterIndiceGuiada() >= 0) {
            guiada.playGuidedFrom(capitulo.indiceParagrafoInicio)
        } else {
            (views.recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(capitulo.indiceParagrafoInicio, 0)
        }
        views.recyclerView.post {
            atualizarProgressoLeitura()
            viewModel.textoEfetivo()?.let { atualizarBarra(it) }
        }
    }

    /**
     * % do livro lido + tempo restante estimado no capítulo (T3.3). Usa o parágrafo em
     * sincronia da leitura guiada quando ativa; senão o primeiro parágrafo visível na tela
     * (chamado também pelo listener de scroll — por isso lê o estado direto do ViewModel,
     * não recebe [state] por parâmetro). Só recalcula quando o parágrafo de referência muda,
     * para não custar nada nos ticks de 20fps da leitura guiada.
     */
    fun atualizarProgressoLeitura() {
        val state = viewModel.uiState.value
        val texto = state.texto
        val indiceRef = callbacks.obterParagrafoAtual()
        if (texto == null || indiceRef == null) {
            views.tvProgresso.visibility = View.GONE
            atualizarDescricaoArea()
            callbacks.atualizarIconeBookmark(null)
            return
        }
        // Fim do livro na tela (sem leitura em andamento): o parágrafo de cima ainda não é o último, mas não falta
        // nada a ler. Antes ficava "89% · ~1 min restantes" com o texto todo já lido.
        val noFim = state.paragrafoSyncAtual < 0 && callbacks.fimDoLivroNaTela()
        val chave = if (noFim) PROGRESSO_CHAVE_FIM else indiceRef
        if (chave == progressoIndiceAplicado) return
        progressoIndiceAplicado = chave
        callbacks.atualizarIconeBookmark(indiceRef)

        val textoAtivo = state.textoLimpo ?: texto
        val progresso = progressoNaBarra(textoAtivo, indiceRef, noFim)
        views.tvProgresso.text = if (progresso.minutos > 0) {
            activity.getString(R.string.progresso_leitura_com_tempo, progresso.percentual, progresso.minutos)
        } else {
            activity.getString(R.string.progresso_leitura_sem_tempo, progresso.percentual)
        }
        views.tvProgresso.visibility = View.VISIBLE
        atualizarDescricaoArea()
        // Rolar com o dedo não muda o estado da tela: a barra do capítulo acompanha por aqui.
        viewModel.textoEfetivo()?.let { atualizarBarra(it) }
    }

    // ── Painel de capítulos (T3.4 — sumário navegável) ────────────────────

    /** Abre o sumário de capítulos, já no capítulo atual. Sem capítulos, não faz nada. */
    fun mostrarPainel() {
        val texto = viewModel.textoEfetivo() ?: return
        if (texto.capitulos.isEmpty()) return
        // Quem abriu o painel já descobriu a barra de capítulos: a dica dela não aparece depois.
        appPrefs.marcarDicaVista(com.jonjonesbr.audiobookgen.domain.Dica.LEITOR_CAPITULOS.id)

        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_capitulos, null)
        dialog.setContentView(view)
        dialog.abrirExpandida()
        painelAberto = true
        dialog.setOnDismissListener { painelAberto = false }

        val atual = capituloInicialDoPainel(
            capituloNaBarra, texto.capitulos.size, viewModel.uiState.value.capituloAtivo
        )
        // Marcar capítulos para converter (o que os chips faziam); não existe no "Ler sem áudio".
        val selecionados = if (somenteLeitura) null else capitulosSelecionados
        val marcarTodos = view.findViewById<TextView>(R.id.tvCapitulosMarcarTodos)
        lateinit var adapter: CapituloTocAdapter
        fun atualizarMarcarTodos() {
            marcarTodos.setText(
                if (adapter.todosVisiveisMarcados()) R.string.capitulos_desmarcar_todos
                else R.string.capitulos_marcar_todos
            )
        }
        adapter = CapituloTocAdapter(
            texto.capitulos, atual, selecionados,
            onCapituloTocado = { indice ->
                dialog.dismiss()
                irParaCapitulo(indice)
            },
            onSelecaoMudou = {
                if (capitulosSelecionados.isNotEmpty()) viewModel.limparSelecao()
                callbacks.aoMudarSelecao()
                atualizarMarcarTodos()
            }
        )
        view.findViewById<View>(R.id.linhaCapitulosConverter).visibility =
            if (selecionados != null) View.VISIBLE else View.GONE
        atualizarMarcarTodos()
        marcarTodos.setOnClickListener { adapter.marcarTodosVisiveis(!adapter.todosVisiveisMarcados()) }
        val lista = view.findViewById<RecyclerView>(R.id.rvCapitulosToc).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
        }
        // Abre já no capítulo atual, com alguns acima dele à vista.
        (lista.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset((adapter.posicaoDoAtual() - CAPITULOS_ACIMA_DO_ATUAL).coerceAtLeast(0), 0)
        if (texto.capitulos.size > MIN_CAPITULOS_PARA_BUSCA) {
            view.findViewById<android.widget.EditText>(R.id.etBuscaCapitulos).apply {
                visibility = View.VISIBLE
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) = adapter.filtrar(s?.toString().orEmpty())
                })
            }
        }

        dialog.show()
    }
}
