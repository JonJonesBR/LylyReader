package com.jonjonesbr.audiobookgen.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.RegraDaRolagemDoLeitor

/**
 * Rolagem, teclas, posição salva e acompanhamento do audiobook no leitor. Extraído de [ReaderActivity] (rb-6).
 * Padrão do projeto: activity + viewModel + recyclerView e callbacks no construtor, sem acessar campos da Activity.
 * O estado da barra guiada (rolagem manual, aguardando o auto scroll) continua no GuidedReadingBarController: aqui
 * ele só é lido por [Callbacks.obterBarraGuiada], a cada uso.
 */
@Suppress("TooManyFunctions") // rolagem, teclas e posição salva são um assunto só
class ReaderRolagemController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val recyclerView: RecyclerViewParagrafos,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterBarraGuiada: () -> GuidedReadingBarController?,
        val obterIndiceGuiada: () -> Int,
        val obterAdapter: () -> ParagrafoAdapter?,
        val reconectandoGuiada: () -> Boolean,
        /** AUTO_START ou CONTINUAR: a guiada faz o próprio scroll ao começar. */
        val guiadaIniciaSozinha: () -> Boolean,
        val posicaoSalva: () -> Int,
        val aoPararDeRolar: () -> Unit,
    )

    /** Modo páginas (rb-26b): páginas virtuais sobre a lista vertical. Esta classe a cria e a mantém. */
    private val paginacao = PaginacaoDoLeitor(
        lista = recyclerView,
        emModoLeitura = { callbacks.obterAdapter()?.modo == AdapterModo.LEITURA },
        aoVirar = {
            callbacks.obterBarraGuiada()?.aguardandoAutoScroll = false
            callbacks.aoPararDeRolar()
        },
        aoVirarPeloLeitor = { rolagemPeloLeitor() },
    )

    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var pagerSnapHelper: PagerSnapHelper? = null
    private var scrollRestaurado = false

    /** Último parágrafo lido pela leitura guiada encerrada nesta tela (-1: nenhum ou o usuário rolou depois). */
    private var paragrafoGuiadoAoEncerrar = -1

    /** Até quando (uptime) a rolagem automática do audiobook fica suspensa depois de o usuário rolar com o dedo. */
    private var rolagemAudiobookSuspensaAte = 0L

    /** Liga o scroll e as entradas de rolagem (dedo, TalkBack, roda, teclado). Chamado uma vez, em onCreate. */
    fun configurar() {
        scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Só o arraste com o dedo chega em DRAGGING: a rolagem automática do código não entra nesse estado.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    rolagemPeloLeitor()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    callbacks.obterBarraGuiada()?.aguardandoAutoScroll = false
                    callbacks.aoPararDeRolar()
                }
            }
        }
        recyclerView.addOnScrollListener(scrollListener!!)
        // As outras entradas de quem lê: TalkBack (ações de rolagem da lista), roda do mouse/touchpad e teclado.
        recyclerView.aoRolagemDeAcessibilidade = { rolagemPeloLeitor() }
        // Rolar o texto dentro da página de um parágrafo longo (rolagem horizontal, rb-26a) é rolagem de quem lê.
        recyclerView.aoRolarDentroDaPagina = { rolagemPeloLeitor() }
        recyclerView.setOnGenericMotionListener { _, evento ->
            if (evento.action == MotionEvent.ACTION_SCROLL) rolagemPeloLeitor()
            false // o RecyclerView continua tratando a roda normalmente
        }
    }

    /**
     * Liga ou desliga o modo páginas da opção "Horizontal (páginas)" (rb-26b). A lista é sempre a vertical: as páginas
     * são a [paginacao] por cima dela, com o mesmo adapter e o mesmo TextView por parágrafo. O layout manager é criado
     * uma vez e não é trocado, porque trocá-lo levaria o leitor ao início do livro. Chamada na criação e ao alternar a
     * opção ao vivo.
     */
    fun aplicarOrientacao(horizontal: Boolean) {
        // Sem o pager por parágrafo (rb-26b, passo 2): o adapter fica sempre no item de texto. O código do pager fica
        // sem uso até o passo 5.
        callbacks.obterAdapter()?.paginado = false
        pagerSnapHelper?.attachToRecyclerView(null)
        pagerSnapHelper = null
        if (recyclerView.layoutManager !is LeitorLayoutManager) {
            recyclerView.layoutManager = LeitorLayoutManager(
                activity,
                LinearLayoutManager.VERTICAL,
                seguindoLeitura = { guiadaConduzRolagem() },
                toqueEmAndamento = { recyclerView.toqueEmAndamento },
                focoSemRolar = { recyclerView.focoSemRolar },
                paginaFixa = { paginacao.ativa },
            ).also { it.aoTerminarSalto = { paginacao.aoTerminarSalto() } }
        }
        recyclerView.paginacao = paginacao
        val ligadaAntes = paginacao.ligada
        val ligou = horizontal && !ligadaAntes
        paginacao.ligada = horizontal
        // O respiro de baixo (EspacoSobControles) depende do modo: o layout refaz a conta no listener global.
        if (paginacao.ligada != ligadaAntes) recyclerView.requestLayout()
        // Ao ligar, o topo vai para a primeira linha inteira: a lista pode ter parado no meio de uma linha.
        if (ligou) recyclerView.post { paginacao.alinharTopo() }
    }

    /**
     * Teclado: as teclas que rolam o texto contam como rolagem de quem lê, mas só quando a lista de fato rola (no fim
     * do livro, PAGE_DOWN não muda nada e não liga o modo manual). A busca, que tem o foco, fica de fora.
     * [padrao] é o tratamento padrão da Activity para a tecla.
     */
    fun tratarTecla(event: KeyEvent, padrao: (KeyEvent) -> Boolean): Boolean {
        val keyCode = event.keyCode
        val sentido = RegraDaRolagemDoLeitor.sentidoDaTecla(keyCode)
        val seta = RegraDaRolagemDoLeitor.sentidoDaSeta(keyCode)
        return when {
            // O RecyclerView não trata as teclas de página: a rolagem é feita aqui, uma tela por vez (no modo páginas,
            // a tela é a página virtual).
            sentido != null -> {
                val virou = if (paginacao.ativa) paginacao.virarPeloLeitor(sentido) else rolarPagina(sentido)
                virou || padrao(event)
            }
            // Modo páginas: a seta vira a página, como a tecla de página. Com Shift ou seleção, a seta vai ao TextView.
            // Com foco num controle fora da lista (botão, chip, barra), a seta segue a navegação de foco.
            paginacao.ativa && seta != null && RegraDaRolagemDoLeitor.setaRolaAPagina(
                shift = event.isShiftPressed,
                temSelecao = recyclerView.selecoesAbertas > 0 || recyclerView.textoComSelecaoViva != null,
                textoFocado = activity.currentFocus == null || recyclerView.hasFocus(),
                podeRolar = true,
            ) -> paginacao.virarPeloLeitor(seta) || padrao(event)
            // Setas: a lista só rola se o foco for para um filho fora da tela. Conta só se o deslocamento mudou.
            RegraDaRolagemDoLeitor.ehSetaVertical(keyCode) -> {
                if (rolarSetaNaPagina(event)) return true
                val antes = deslocamentoAtual()
                val tratada = padrao(event)
                if (deslocamentoAtual() != antes) rolagemPeloLeitor()
                tratada
            }
            else -> padrao(event)
        }
    }

    /**
     * Scroll automático do audiobook tocando (não da leitura guiada). Só rola quando o parágrafo muda — atualizarUI
     * roda a cada tick de progresso (20fps) e re-rolar para o mesmo índice causava flicker (#3).
     */
    fun sincronizarComAudiobook(state: ReaderUiState) {
        val controlador = callbacks.obterBarraGuiada() ?: return
        // Na leitura guiada o acompanhamento fino (GuidedReadingBarController) já rola; este salto até o
        // parágrafo brigava com ele (subia para o topo ou descia até o fim do parágrafo a cada troca).
        if (callbacks.obterIndiceGuiada() >= 0 || recyclerView.textoEmUso) return
        val podeRolarAutomaticamente = RegraDaRolagemDoLeitor.podeRolarComAudiobook(
            SystemClock.uptimeMillis(),
            rolagemAudiobookSuspensaAte,
            state.syncAtivo,
            state.paragrafoSyncAtual,
            controlador.ultimoScrollSyncStandard,
        )
        if (podeRolarAutomaticamente) {
            controlador.ultimoScrollSyncStandard = state.paragrafoSyncAtual
            val lm = recyclerView.layoutManager as? LinearLayoutManager
            // No modo páginas a virada é sem animação; o topo é alinhado depois do layout (ver PaginacaoDoLeitor).
            if (paginacao.ativa) lm?.scrollToPositionWithOffset(state.paragrafoSyncAtual, 0)
            else lm?.smoothScrollToPosition(recyclerView, null, state.paragrafoSyncAtual)
        }
    }

    /**
     * Restaura a posição de leitura salva — apenas uma vez, no carregamento inicial, e nunca quando a leitura guiada
     * vai iniciar sozinha (ela faz seu próprio scroll).
     */
    fun restaurarPosicaoSeNecessario(paragrafos: List<Paragrafo>) {
        if (scrollRestaurado || paragrafos.isEmpty()) return
        scrollRestaurado = true
        // Reconexão: rola para o parágrafo em execução, não para o scroll salvo.
        val alvo = RegraDaRolagemDoLeitor.posicaoARestaurar(
            callbacks.reconectandoGuiada(),
            callbacks.obterIndiceGuiada(),
            callbacks.guiadaIniciaSozinha(),
            callbacks.posicaoSalva(),
            paragrafos.size,
        ) ?: return
        recyclerView.post {
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(alvo, 0)
        }
    }

    /**
     * Parágrafo de referência pra leitura manual: o em sincronia da leitura guiada quando
     * ativa; senão o primeiro parágrafo visível na tela. Usado tanto pra progresso de leitura
     * quanto pra marcadores — extraído pra não duplicar a lógica (era duplicada, com um bug:
     * marcadores usavam `findFirstCompletelyVisibleItemPosition`, que retorna -1 sempre que
     * nenhum parágrafo cabe inteiro na tela — comum com parágrafos longos — e o marcador
     * acabava sendo salvo no início do livro em vez da posição real).
     */
    fun paragrafoAtualVisivel(): Int? {
        val state = viewModel.uiState.value
        return RegraDaRolagemDoLeitor.paragrafoDeReferencia(
            state.texto != null,
            state.paragrafoSyncAtual,
            (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition(),
        )
    }

    /**
     * Primeiro parágrafo visível na tela, sem olhar a leitura guiada (ao contrário de [paragrafoAtualVisivel]).
     * Nulo se não há texto ou nenhum item visível.
     */
    fun primeiroParagrafoNaTela(): Int? {
        if (viewModel.uiState.value.texto == null) return null
        val primeiro = (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: -1
        return primeiro.takeIf { it >= 0 }
    }

    /**
     * Parágrafo da tela para amostra e compartilhar (rb-34b): se a pessoa rolou para longe da narração,
     * o primeiro visível na tela; senão, o parágrafo de referência de [paragrafoAtualVisivel].
     */
    fun paragrafoDaTela(rolouParaLonge: Boolean): Int? =
        if (rolouParaLonge) primeiroParagrafoNaTela() else paragrafoAtualVisivel()

    /** A lista chegou ao fim (não rola mais para baixo, ou para a frente na rolagem horizontal) e há texto. */
    fun fimDoLivroNaTela(): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        // Antes do primeiro layout a lista "não rola" por não ter nada medido: isso não é o fim do livro.
        val medida = (callbacks.obterAdapter()?.itemCount ?: 0) > 0 && recyclerView.isLaidOut &&
            recyclerView.childCount > 0
        return medida && if (lm.orientation == LinearLayoutManager.HORIZONTAL) {
            !recyclerView.canScrollHorizontally(1)
        } else {
            !recyclerView.canScrollVertically(1)
        }
    }

    /** Guarda o parágrafo do fim da leitura guiada; é lido ao sair da tela. Chamado pelo GuidedReadingBarController. */
    fun aoEncerrarGuiada(indice: Int) {
        paragrafoGuiadoAoEncerrar = indice
    }

    /** Parágrafo a gravar ao sair da tela (ver RegraDaRolagemDoLeitor.paragrafoParaSalvar). Nulo: nada a gravar. */
    fun paragrafoParaSalvar(): Int? = RegraDaRolagemDoLeitor.paragrafoParaSalvar(
        paragrafoGuiadoAoEncerrar,
        viewModel.uiState.value.paragrafoSyncAtual,
        fimDoLivroNaTela(),
        callbacks.obterAdapter()?.itemCount ?: 0,
        paragrafoAtualVisivel(),
    )

    /** Solta o scroll listener. A Activity só chama depois de confirmar que a lista foi montada. */
    fun liberar() {
        scrollListener?.let { recyclerView.removeOnScrollListener(it) }
        scrollListener = null
    }

    /** Leitura guiada ativa e o usuário não rolou com o dedo: só o acompanhamento dela mexe na rolagem. */
    private fun guiadaConduzRolagem(): Boolean =
        RegraDaRolagemDoLeitor.guiadaConduzRolagem(callbacks.obterIndiceGuiada()) {
            callbacks.obterBarraGuiada()?.scrollManual == true
        }

    /**
     * Rolagem iniciada por quem lê, qualquer que seja a entrada (dedo, TalkBack, teclado ou roda). Liga o modo manual
     * da leitura guiada, que só se desliga com um pedido explícito (ver GuidedReadingBarController).
     */
    private fun rolagemPeloLeitor() {
        callbacks.obterBarraGuiada()?.ligarRolagemManual()
        // Rolou por conta própria: a posição a guardar volta a ser a da tela, não a da leitura guiada encerrada.
        paragrafoGuiadoAoEncerrar = -1
        // Rolar suspende a rolagem automática do audiobook por um tempo; antes desligava o acompanhamento de vez
        // (o destaque parava até reabrir o leitor).
        rolagemAudiobookSuspensaAte = SystemClock.uptimeMillis() + RegraDaRolagemDoLeitor.PAUSA_ROLAGEM_MANUAL_MS
    }

    /**
     * Seta vertical com a página parada, o TextView dela focado e o texto ainda rolável (rb-26c): a página rola,
     * e a seta não chega ao ArrowKeyMovementMethod do TextView, que só moveria um cursor invisível. Com Shift ou
     * seleção aberta, a seta continua indo ao TextView, que estende a seleção. Conta como rolagem de quem lê só se
     * a página de fato rolou (o arrowScroll pode só mover o foco). Falso se a seta não rolou a página.
     */
    private fun rolarSetaNaPagina(event: KeyEvent): Boolean {
        val sentido = RegraDaRolagemDoLeitor.sentidoDaSeta(event.keyCode)
        val pagina = recyclerView.paginaParadaNaTela()
        if (pagina == null || sentido == null || !RegraDaRolagemDoLeitor.setaRolaAPagina(
                shift = event.isShiftPressed,
                temSelecao = pagina.texto.hasSelection(),
                textoFocado = pagina.texto.isFocused,
                podeRolar = pagina.canScrollVertically(sentido),
            )
        ) {
            return false
        }
        val antes = pagina.scrollY
        pagina.arrowScroll(if (sentido > 0) View.FOCUS_DOWN else View.FOCUS_UP)
        val rolou = pagina.scrollY != antes
        if (rolou) rolagemPeloLeitor()
        return rolou
    }

    /** Rola uma tela para trás (-1) ou para frente (1), no eixo da lista. Falso se a lista já está no limite. */
    private fun rolarPagina(sentido: Int): Boolean {
        // Rolagem horizontal com parágrafo longo: a página rola na vertical antes de virar (rb-26a).
        val pagina = recyclerView.paginaParadaNaTela()
        if (pagina != null && pagina.canScrollVertically(sentido)) {
            pagina.rolarUmaTela(sentido)
            rolagemPeloLeitor()
            return true
        }
        val vertical = recyclerView.layoutManager?.canScrollVertically() == true
        val podeRolar = if (vertical) {
            recyclerView.canScrollVertically(sentido)
        } else {
            recyclerView.canScrollHorizontally(sentido)
        }
        if (podeRolar) {
            if (vertical) {
                recyclerView.smoothScrollBy(0, sentido * recyclerView.height)
            } else {
                recyclerView.smoothScrollBy(sentido * recyclerView.width, 0)
            }
            rolagemPeloLeitor()
        }
        return podeRolar
    }

    /**
     * Deslocamento da lista no eixo atual, para saber se uma tecla mexeu nela. Na horizontal, soma a rolagem da página
     * parada (rb-26a), para que a rolagem do texto dentro dela também conte como rolagem de quem lê.
     */
    private fun deslocamentoAtual(): Int =
        if (recyclerView.layoutManager?.canScrollVertically() == true) {
            recyclerView.computeVerticalScrollOffset()
        } else {
            recyclerView.computeHorizontalScrollOffset() + (recyclerView.paginaParadaNaTela()?.scrollY ?: 0)
        }
}
