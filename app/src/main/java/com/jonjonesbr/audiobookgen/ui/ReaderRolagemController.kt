package com.jonjonesbr.audiobookgen.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
        recyclerView.setOnGenericMotionListener { _, evento ->
            if (evento.action == MotionEvent.ACTION_SCROLL) rolagemPeloLeitor()
            false // o RecyclerView continua tratando a roda normalmente
        }
    }

    /** Alterna o RecyclerView entre scroll vertical contínuo (padrão) e horizontal paginado
     * (com PagerSnapHelper — "página" por página é o mesmo parágrafo, unidade de navegação
     * inalterada). Chamada tanto na criação quanto ao alternar a opção ao vivo. */
    fun aplicarOrientacao(horizontal: Boolean) {
        pagerSnapHelper?.attachToRecyclerView(null)
        recyclerView.layoutManager = LeitorLayoutManager(
            activity,
            if (horizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        ) { guiadaConduzRolagem() }
        if (horizontal) {
            pagerSnapHelper = PagerSnapHelper().apply {
                attachToRecyclerView(recyclerView)
            }
        } else {
            pagerSnapHelper = null
        }
    }

    /**
     * Teclado: as teclas que rolam o texto contam como rolagem de quem lê, mas só quando a lista de fato rola (no fim
     * do livro, PAGE_DOWN não muda nada e não liga o modo manual). A busca, que tem o foco, fica de fora.
     * [padrao] é o tratamento padrão da Activity para a tecla.
     */
    fun tratarTecla(event: KeyEvent, padrao: (KeyEvent) -> Boolean): Boolean {
        val keyCode = event.keyCode
        val sentido = RegraDaRolagemDoLeitor.sentidoDaTecla(keyCode)
        return when {
            // O RecyclerView não trata as teclas de página: a rolagem é feita aqui, uma tela por vez.
            sentido != null -> rolarPagina(sentido) || padrao(event)
            // Setas: a lista só rola se o foco for para um filho fora da tela. Conta só se o deslocamento mudou.
            RegraDaRolagemDoLeitor.ehSetaVertical(keyCode) -> {
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
        if (callbacks.obterIndiceGuiada() >= 0) return
        val podeRolarAutomaticamente = RegraDaRolagemDoLeitor.podeRolarComAudiobook(
            SystemClock.uptimeMillis(),
            rolagemAudiobookSuspensaAte,
            state.syncAtivo,
            state.paragrafoSyncAtual,
            controlador.ultimoScrollSyncStandard,
        )
        if (podeRolarAutomaticamente) {
            controlador.ultimoScrollSyncStandard = state.paragrafoSyncAtual
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.smoothScrollToPosition(recyclerView, null, state.paragrafoSyncAtual)
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

    /** Rola uma tela para trás (-1) ou para frente (1), no eixo da lista. Falso se a lista já está no limite. */
    private fun rolarPagina(sentido: Int): Boolean {
        val vertical = recyclerView.layoutManager?.canScrollVertically() == true
        val podeRolar = if (vertical) {
            recyclerView.canScrollVertically(sentido)
        } else {
            recyclerView.canScrollHorizontally(sentido)
        }
        if (!podeRolar) return false
        if (vertical) {
            recyclerView.smoothScrollBy(0, sentido * recyclerView.height)
        } else {
            recyclerView.smoothScrollBy(sentido * recyclerView.width, 0)
        }
        rolagemPeloLeitor()
        return true
    }

    /** Deslocamento da lista no eixo atual, para saber se uma tecla mexeu nela. */
    private fun deslocamentoAtual(): Int =
        if (recyclerView.layoutManager?.canScrollVertically() == true) {
            recyclerView.computeVerticalScrollOffset()
        } else {
            recyclerView.computeHorizontalScrollOffset()
        }
}
