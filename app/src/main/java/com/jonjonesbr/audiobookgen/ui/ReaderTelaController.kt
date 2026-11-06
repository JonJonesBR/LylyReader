package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TelaDica
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.util.aplicarMargemInferiorSoNaSobreposicao
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorSoNaSobreposicao
import com.jonjonesbr.audiobookgen.util.dpToPx

/** Respiro entre o botão "Voltar à narração" e a barra da leitura guiada (que fica na base da tela). */
private const val MARGEM_FAB_VOLTAR_DP = 16
private const val DURACAO_FADE_BANNER_MS = 300L
private const val DURACAO_PILL_LIMPEZA_MS = 5000L

/**
 * Aplica na tela do leitor o estado calculado em TelaDoLeitor.kt: carregando e erro, pílula da limpeza, barra de modo,
 * FABs, tema e margem, a dica e as barras de cima do modo páginas (rb-66, ver [BarrasDoLeitor]). Por instância
 * da Activity (a dica, o tema e a margem zeram ao girar). A pílula já mostrada fica no ViewModel ([aoMostrarPilula] a
 * marca), para não voltar ao girar.
 * Não lê o ViewModel: o que a tela precisa chega por parâmetro ou por lambda.
 */
class ReaderTelaController(
    private val activity: AppCompatActivity,
    private val recyclerView: RecyclerView,
    private val views: Views,
    private val somenteLeitura: Boolean,
    private val aoMostrarPilula: () -> Unit,
) {

    /** As views da tela do leitor, já encontradas pela Activity. */
    data class Views(
        val barraCapitulo: View, val layoutCarregando: View, val tvCarregando: TextView,
        val layoutErro: View, val tvErro: TextView, val btnConverterAssim: View,
        val fabConverter: ExtendedFloatingActionButton, val fabVoltarNarracao: ExtendedFloatingActionButton,
        val layoutGuidedPlayer: View, val banner: View, val tvBannerInfo: TextView,
        val layoutBarraModo: View, val tvModoAtivo: TextView, val btnOpcoes: View, val readerRoot: View,
        val barraSelecao: View, val tvSelecao: TextView,
    )

    // Uma dica por abertura do leitor.
    private var dicaTentada = false
    private var margemNivelAplicado = -1
    private var temaAplicado: TemaLeitura? = null
    // Última contagem mostrada na barra de seleção: o aplicarStatus roda ~20x/s e um setText repetido pede layout.
    private var selecionadosNaBarra = -1
    // "Voltar à narração" dentro do painel da guiada e o status da barra (rb-26b, passo 4): fora da Views, para não
    // mexer na montagem da Activity. Achados uma vez, quando o FAB é aplicado.
    private val botaoVoltarNoPainel: View by lazy { activity.findViewById(R.id.btnGuidedVoltarNarracao) }
    private val tvStatusDoPainel: View by lazy { activity.findViewById(R.id.tvGuidedStatus) }
    // Barras de cima recolhíveis do modo páginas (rb-66, passo 3): o coordenador decide o cabeçalho e a reserva.
    // Achado uma vez, como os de cima. Só existe com a lista de páginas (RecyclerViewParagrafos).
    private val barras: BarrasDoLeitor? by lazy {
        (recyclerView as? RecyclerViewParagrafos)?.let { BarrasDoLeitor(activity, it) }
    }

    /**
     * Chamado uma vez, no vincularViews, depois do recyclerView. Mesma ordem de efeitos de antes: insets, listeners do
     * FAB e da barra guiada, espaço sobre os controles, e o "Ler sem áudio" por último.
     */
    fun configurar() {
        // T6.1 (edge-to-edge): FAB e barra de leitura guiada são ancorados na base da tela —
        // sem isso, ficam atrás da barra de gestos em telas sem botões de navegação físicos.
        // rb-26b (passo 1): o pai (fitsSystemWindows) já põe estas views acima da barra;
        // aqui só entra a sobreposição real, sem somar a barra de novo.
        views.fabConverter.aplicarMargemInferiorSoNaSobreposicao()
        views.layoutGuidedPlayer.aplicarPaddingInferiorSoNaSobreposicao()
        // A barra guiada muda de altura (chips, modo manual): o "Voltar à narração" acompanha a borda de cima dela.
        views.layoutGuidedPlayer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            posicionarFabVoltarNarracao()
        }
        // ux-5: o mini player fica por cima do FAB. O OnLayoutChangeListener não dispara quando ele vira GONE,
        // por isso o listener é global.
        val miniPlayer: View? = activity.findViewById(R.id.miniPlayerRoot)
        miniPlayer?.let { mini ->
            mini.viewTreeObserver.addOnGlobalLayoutListener { posicionarFabAcimaDoMiniPlayer(mini) }
        }
        // rb-26b (passo 3): a barra da seleção entra no respiro só quando está embaixo, cedendo o lugar do painel.
        val barraDeTexto: View? = activity.findViewById(R.id.barraSelecaoTexto)
        EspacoSobControles.manter(recyclerView) {
            listOfNotNull(
                views.layoutGuidedPlayer,
                miniPlayer,
                // Com a tira do painel na base, a barra da seleção não conta: a página não muda de tamanho
                // (rb-66, passo 5).
                barraDeTexto?.takeIf {
                    (recyclerView as? RecyclerViewParagrafos)?.baseCedidaASelecao == true &&
                        barras?.painelRecolhidoNaBase != true
                },
            )
        }
        // rb-51: a descida da barra da seleção vira espaço depois do último parágrafo (decoração, não padding). A lista
        // avisa quando a descida muda, e a decoração é refeita.
        (recyclerView as? RecyclerViewParagrafos)?.let { lista ->
            lista.addItemDecoration(EspacoDaDescidaNoFim(lista))
            lista.aoMudarDescida = { lista.invalidateItemDecorations() }
        }
        if (somenteLeitura) {
            // "Ler sem áudio": sem FAB de conversão, sem barra da leitura guiada e sem "converter assim mesmo".
            views.fabConverter.visibility = View.GONE
            views.layoutGuidedPlayer.visibility = View.GONE
            views.btnConverterAssim.visibility = View.GONE
        }
        // rb-66 (passo 3): o coordenador liga a tela cheia (avisos da lista), o toque nas barras, o tempo e o foco.
        barras?.ligar()
    }

    /**
     * Carregando e erro. Devolve true quando há erro: a Activity para ali, sem tocar no texto.
     * Mesma ordem de antes: o aviso de carregamento vem antes do erro.
     */
    internal fun aplicarCarregandoEErro(tela: TelaDoLeitor): Boolean {
        mostrarCarregando(tela.carregandoAoAbrir)
        val erro = tela.erro
        if (erro != null) {
            views.layoutErro.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            views.barraCapitulo.visibility = View.GONE
            // Com erro, o cabeçalho (com o botão de voltar) fica sempre visível.
            barras?.aplicar(leitura = false, paginas = false)
            views.tvErro.text = erro
            return true
        }
        views.layoutErro.visibility = View.GONE
        return false
    }

    /** Pílula da limpeza e barra de modo ativo, com o texto já na tela. */
    internal fun aplicarStatus(tela: TelaDoLeitor, texto: TextoExtraido) {
        when (tela.pilula) {
            PilulaDaLimpeza.OCULTAR -> {
                if (tela.carregando == CarregandoDoLeitor.PREPARANDO) mostrarCarregando(CarregandoDoLeitor.PREPARANDO)
                views.banner.visibility = View.GONE
            }
            PilulaDaLimpeza.ANIMAR, PilulaDaLimpeza.NAO_MEXER -> {
                mostrarCarregando(null)
                if (tela.pilula == PilulaDaLimpeza.ANIMAR) {
                    // O resultado da limpeza aparece uma vez, por alguns segundos, e só quando algo foi tirado
                    // (antes ficava sempre na tela, inclusive com "Otimização em andamento" ao abrir).
                    aoMostrarPilula()
                    views.banner.alpha = 0f
                    views.banner.visibility = View.VISIBLE
                    views.banner.animate().alpha(1f).setDuration(DURACAO_FADE_BANNER_MS).start()
                    views.banner.postDelayed({
                        views.banner.animate().alpha(0f).setDuration(DURACAO_FADE_BANNER_MS)
                            .withEndAction { views.banner.visibility = View.GONE }.start()
                    }, DURACAO_PILL_LIMPEZA_MS)
                }
                views.tvBannerInfo.text = activity.resources.getQuantityString(
                    R.plurals.banner_info_trechos_capitulos_qtd,
                    tela.linhasRemovidas,
                    tela.linhasRemovidas,
                    texto.capitulos.size
                )
            }
        }

        // Barra de modo ativo
        views.layoutBarraModo.visibility = if (tela.rotuloModo != null) View.VISIBLE else View.GONE
        tela.rotuloModo?.let { rotulo ->
            views.tvModoAtivo.text = activity.getString(
                when (rotulo) {
                    RotuloDoModo.EDICAO -> R.string.modo_edicao_label
                    RotuloDoModo.COMPARACAO -> R.string.modo_comparacao_label
                }
            )
        }
        aplicarBarraDeSelecao(tela.paragrafosSelecionados)
        // Barras de cima: o modo vem da tela (tela.modoAdapter), porque o adapter só é atualizado depois deste ponto.
        val paginacao = (recyclerView as? RecyclerViewParagrafos)?.paginacao
        val leitura = tela.modoAdapter == AdapterModo.LEITURA
        barras?.aplicar(leitura = leitura, paginas = paginacao?.ligada == true && leitura)
    }

    /**
     * Barra "N parágrafos selecionados · Cancelar · Gerar áudio" (rb-30). Só mexe quando a contagem muda.
     * Aparece com a guiada parada, tocando ou pausada: fica no topo, então não disputa lugar com a barra da guiada.
     */
    private fun aplicarBarraDeSelecao(quantos: Int) {
        if (quantos == selecionadosNaBarra) return
        selecionadosNaBarra = quantos
        views.barraSelecao.visibility = if (quantos > 0) View.VISIBLE else View.GONE
        if (quantos > 0) {
            views.tvSelecao.text =
                activity.resources.getQuantityString(R.plurals.barra_selecao_paragrafos, quantos, quantos)
        }
    }

    /**
     * Visibilidade e rótulo dos FABs (a regra está em [estadoDosFabs]). O "Voltar à narração" vai para o chip ou
     * para o painel conforme o modo (ver [lugarDoVoltarANarracao]). No painel, o status da guiada sai para caber o
     * botão.
     */
    internal fun aplicarFabs(fabs: FabsDoLeitor) {
        val paginas = (recyclerView as? RecyclerViewParagrafos)?.paginacao?.ativa == true
        val lugar = lugarDoVoltarANarracao(fabs.voltarNarracao, paginas)
        views.fabVoltarNarracao.visibility = if (lugar == LugarDoVoltar.CHIP) View.VISIBLE else View.GONE
        botaoVoltarNoPainel.visibility = if (lugar == LugarDoVoltar.PAINEL) View.VISIBLE else View.GONE
        tvStatusDoPainel.visibility = if (lugar == LugarDoVoltar.PAINEL) View.GONE else View.VISIBLE
        views.fabConverter.visibility = if (fabs.converter) View.VISIBLE else View.GONE
        views.fabConverter.setIconResource(R.drawable.ic_gerar_audio)
        val capitulos = fabs.capitulosNoRotulo
        views.fabConverter.text =
            activity.resources.getQuantityString(R.plurals.fab_converter_capitulos, capitulos, capitulos)
    }

    /**
     * Tema do fundo e margem horizontal da lista. Roda a cada atualização da tela e só repinta quando muda.
     * Os dois blocos são independentes: sem `return` antecipado, senão o tema pularia a margem.
     */
    fun aplicarTemaEMargem(state: ReaderUiState) {
        if (temaAplicado != state.tema) {
            temaAplicado = state.tema
            val paleta = paletaDoLeitor(state.tema)
            views.readerRoot.setBackgroundColor(paleta.fundo)
            activity.aplicarPaletaNasBarrasDoLeitor(paleta)
        }
        // Margem horizontal da lista de parágrafos (0=estreita, 1=média, 2=larga).
        if (margemNivelAplicado != state.margemNivel) {
            margemNivelAplicado = state.margemNivel
            val margemPx = activity.dpToPx(margemDosParagrafosDp(state.margemNivel))
            recyclerView.setPadding(margemPx, recyclerView.paddingTop, margemPx, recyclerView.paddingBottom)
        }
    }

    /**
     * Dica da tela do leitor, uma vez por abertura: primeiro o menu ⋮, na seguinte a barra de capítulos.
     * [painelAberto] é lido dentro do `post`, como antes, e não no momento da chamada.
     */
    fun tentarDica(painelAberto: () -> Boolean) {
        if (dicaTentada || somenteLeitura) return
        dicaTentada = true
        views.btnOpcoes.post {
            if (painelAberto()) return@post
            CoachMark.mostrarProxima(activity, TelaDica.LEITOR) { dica -> alvoDaDicaDoLeitor(activity, dica) }
        }
    }

    /**
     * Mantém o "Voltar à narração" logo acima da barra da leitura guiada. O chip só aparece com a barra visível,
     * então a folga vale sempre; a altura da barra já inclui a barra de gestos.
     */
    private fun posicionarFabVoltarNarracao() {
        val barra = views.layoutGuidedPlayer
        // Não VISIBLE: com a seleção embaixo, o painel fica INVISIBLE (rb-26b, passo 3) e o chip segue a base.
        val alvo = (if (barra.visibility != View.GONE) barra.height else 0) + activity.dpToPx(MARGEM_FAB_VOLTAR_DP)
        val params = views.fabVoltarNarracao.layoutParams as ViewGroup.MarginLayoutParams
        // Só mexe quando muda. Este método roda dentro do layout da barra: o requestLayout vai para o próximo
        // ciclo, senão o ViewRootImpl adia a margem para outro frame (e pode avisar "requestLayout during layout").
        if (params.bottomMargin != alvo) {
            params.bottomMargin = alvo
            views.fabVoltarNarracao.post { views.fabVoltarNarracao.requestLayout() }
        }
    }

    /**
     * Com o mini player visível, o FAB de conversão sobe pela altura útil dele (ux-5). A altura inclui a barra de
     * gestos pelo padding, e a margem do FAB também a inclui, então o padding é descontado. Usa translationY: a
     * margem é reescrita pelo listener de insets.
     */
    private fun posicionarFabAcimaDoMiniPlayer(miniPlayer: View) {
        val alvo = if (miniPlayer.visibility == View.VISIBLE && miniPlayer.height > 0) {
            -(miniPlayer.height - miniPlayer.paddingBottom).toFloat()
        } else {
            0f
        }
        // Só atribui quando muda: o listener roda a cada layout da janela.
        if (views.fabConverter.translationY != alvo) views.fabConverter.translationY = alvo
    }

    /** Mostra ou esconde o aviso de carregamento: nulo esconde; abrindo ou preparando mostra o texto certo. */
    private fun mostrarCarregando(carregando: CarregandoDoLeitor?) {
        views.layoutCarregando.visibility = if (carregando != null) View.VISIBLE else View.GONE
        when (carregando) {
            null -> Unit
            CarregandoDoLeitor.ABRINDO -> views.tvCarregando.setText(R.string.leitor_abrindo_livro)
            CarregandoDoLeitor.PREPARANDO -> views.tvCarregando.setText(R.string.leitor_preparando_texto)
        }
    }
}

/**
 * Alvo das dicas do Leitor que apontam para o cabeçalho (rb-66, passo 7): o menu ⋮ e a barra de capítulos. As outras
 * têm alvo próprio, dado por quem as mostra (ver [DicasDasBarras] e [BarrasDoLeitor]). Nulo para as outras: a dica não
 * aparece e não é marcada como vista. Com o cabeçalho recolhido, o [CoachMark] também não mostra (alvo oculto).
 */
internal fun alvoDaDicaDoLeitor(activity: AppCompatActivity, dica: Dica): View? = when (dica) {
    Dica.LEITOR_GUIADA -> activity.findViewById<View>(R.id.btnReaderOpcoes)
    Dica.LEITOR_CAPITULOS -> activity.findViewById<View>(R.id.barraCapitulo)
    else -> null
}
