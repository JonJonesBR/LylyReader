package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.ReaderActivity

/**
 * Toques e arrastes reais no emulador (rb-46 U3): eventos de toque injetados pelo UiAutomation, como se o dedo
 * tivesse tocado a tela. Os gestos rodam sempre na thread do teste (nunca na principal, que fica livre para tratar
 * os eventos). As leituras de View (posição de palavra, alça da seleção) passam pela main com `onActivity`.
 *
 * Coordenadas em pixels de tela. As posições são heurísticas sobre o layout do TextView: a alça da seleção não
 * tem API pública de posição, então [pontoDaAlcaFinal] calcula onde ela fica e o teste pode tentar outros
 * deslocamentos.
 */
object Gestos {

    /** Duração do toque curto: DOWN, espera e UP. */
    private const val DURACAO_TOQUE_MS = 50L

    /** Pausa depois do DOWN, para a view registrar o toque antes do primeiro movimento (ex.: a alça). */
    private const val PAUSA_APOS_DOWN_MS = 50L

    /** Toque curto: um DOWN e um UP em (x, y), com [DURACAO_TOQUE_MS] de intervalo. */
    fun toqueCurto(x: Float, y: Float) {
        checarThreadDoTeste()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, inicio, inicio, x, y)
        var soltou = false
        try {
            Thread.sleep(DURACAO_TOQUE_MS)
            injetar(MotionEvent.ACTION_UP, inicio, SystemClock.uptimeMillis(), x, y)
            soltou = true
        } finally {
            if (!soltou) cancelarToque(inicio, x, y)
        }
    }

    /** Toque longo: segura em (x, y) por [ms] (o padrão é o dobro do tempo de toque longo do sistema). */
    fun toqueLongo(x: Float, y: Float, ms: Long = 2L * ViewConfiguration.getLongPressTimeout()) {
        checarThreadDoTeste()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, inicio, inicio, x, y)
        var soltou = false
        try {
            Thread.sleep(ms)
            injetar(MotionEvent.ACTION_UP, inicio, SystemClock.uptimeMillis(), x, y)
            soltou = true
        } finally {
            if (!soltou) cancelarToque(inicio, x, y)
        }
    }

    /**
     * Arrasto de (x0, y0) até (x1, y1) em [passos] movimentos de [msPorPasso] cada, com DOWN e UP.
     *
     * Os callbacks são só para diagnóstico (rb-46diag): [aoMover] roda na thread do teste logo depois de cada MOVE
     * injetado, com o número do passo e a posição do dedo; [antesDeSoltar] roda logo antes do UP. Os dois ficam
     * dentro do try, então se falharem o toque ainda é cancelado.
     */
    fun arrastar(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        passos: Int = 20,
        msPorPasso: Long = 15L,
        aoMover: ((passo: Int, x: Float, y: Float) -> Unit)? = null,
        antesDeSoltar: (() -> Unit)? = null
    ) {
        checarThreadDoTeste()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, inicio, inicio, x0, y0)
        var xAtual = x0
        var yAtual = y0
        var soltou = false
        try {
            Thread.sleep(PAUSA_APOS_DOWN_MS)
            for (passo in 1..passos) {
                Thread.sleep(msPorPasso)
                val fracao = passo.toFloat() / passos
                xAtual = x0 + (x1 - x0) * fracao
                yAtual = y0 + (y1 - y0) * fracao
                injetar(MotionEvent.ACTION_MOVE, inicio, SystemClock.uptimeMillis(), xAtual, yAtual)
                aoMover?.invoke(passo, xAtual, yAtual)
            }
            antesDeSoltar?.invoke()
            injetar(MotionEvent.ACTION_UP, inicio, SystemClock.uptimeMillis(), x1, y1)
            soltou = true
        } finally {
            if (!soltou) cancelarToque(inicio, xAtual, yAtual)
        }
    }

    /**
     * Melhor esforço para não deixar um dedo "preso" na tela quando um evento do meio do gesto falha: injeta um
     * ACTION_CANCEL na última posição. Erro aqui é ignorado, para não esconder a falha original.
     */
    private fun cancelarToque(inicio: Long, x: Float, y: Float) {
        runCatching { injetar(MotionEvent.ACTION_CANCEL, inicio, SystemClock.uptimeMillis(), x, y) }
    }

    /**
     * Centro da palavra [palavra] no parágrafo [indiceParagrafo], em pixels de tela. Usa a metade da palavra e a
     * linha do layout onde ela está. Falha se a palavra não estiver no parágrafo.
     */
    fun pontoDaPalavra(
        scenario: ActivityScenario<ReaderActivity>,
        indiceParagrafo: Int,
        palavra: String
    ): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val inicio = tv.text.toString().indexOf(palavra)
            check(inicio >= 0) { "A palavra '$palavra' não está no parágrafo $indiceParagrafo" }
            val offset = inicio + palavra.length / 2
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val linha = layout.getLineForOffset(offset)
            val x = layout.getPrimaryHorizontal(offset)
            val y = (layout.getLineTop(linha) + layout.getLineBottom(linha)) / 2f
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para a palavra '$palavra'" }
    }

    /**
     * Meio da primeira letra de [palavra] no parágrafo [indiceParagrafo], em pixels de tela, na linha onde ela está.
     * Mais preciso que [pontoDaPalavra] para alvos colados numa fronteira (rb-46diag: o 'A' antes de 'chuva', que
     * fica a uma letra e um espaço do início da seleção). Falha se a palavra não estiver no parágrafo.
     */
    fun pontoDoMeioDaPrimeiraLetra(
        scenario: ActivityScenario<ReaderActivity>,
        indiceParagrafo: Int,
        palavra: String
    ): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val inicio = tv.text.toString().indexOf(palavra)
            check(inicio >= 0) { "A palavra '$palavra' não está no parágrafo $indiceParagrafo" }
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val linha = layout.getLineForOffset(inicio)
            val x = (layout.getPrimaryHorizontal(inicio) + layout.getPrimaryHorizontal(inicio + 1)) / 2f
            val y = (layout.getLineTop(linha) + layout.getLineBottom(linha)) / 2f
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para a palavra '$palavra'" }
    }

    /**
     * Onde fica a alça do fim da seleção do parágrafo [indiceParagrafo], em pixels de tela (heurística): 6 dp à
     * direita do fim da seleção e [abaixoDp] dp abaixo da base da linha do fim. Falha se nada estiver selecionado.
     *
     * Esse ponto é só onde o dedo pega a alça. O arraste do teste é relativo: o dedo anda o quanto a alça deve
     * andar (ver [pontoDoFimDaSelecao]), porque o Android mantém a distância entre o dedo e a ponta da alça. Soltar
     * o dedo direto no alvo põe a ponta uma linha acima.
     */
    fun pontoDaAlcaFinal(
        scenario: ActivityScenario<ReaderActivity>,
        indiceParagrafo: Int,
        abaixoDp: Float = 12f
    ): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val densidade = tv.resources.displayMetrics.density
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val fim = tv.selectionEnd
            check(fim >= 0) { "Nada selecionado no parágrafo $indiceParagrafo" }
            val linha = layout.getLineForOffset(fim)
            val x = layout.getPrimaryHorizontal(fim) + 6 * densidade
            val y = layout.getLineBottom(linha) + abaixoDp * densidade
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para a alça do parágrafo $indiceParagrafo" }
    }

    /**
     * Onde fica a alça do início da seleção do parágrafo [indiceParagrafo], em pixels de tela (heurística,
     * espelho de [pontoDaAlcaFinal]): 6 dp à esquerda do início da seleção e [abaixoDp] dp abaixo da base da
     * linha do início. Falha se nada estiver selecionado.
     */
    fun pontoDaAlcaInicial(
        scenario: ActivityScenario<ReaderActivity>,
        indiceParagrafo: Int,
        abaixoDp: Float = 12f
    ): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val densidade = tv.resources.displayMetrics.density
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val inicio = tv.selectionStart
            check(inicio >= 0) { "Nada selecionado no parágrafo $indiceParagrafo" }
            val linha = layout.getLineForOffset(inicio)
            val x = layout.getPrimaryHorizontal(inicio) - 6 * densidade
            val y = layout.getLineBottom(linha) + abaixoDp * densidade
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para a alça inicial do parágrafo $indiceParagrafo" }
    }

    /**
     * Ponto do início da seleção do parágrafo [indiceParagrafo], em pixels de tela: a posição horizontal do
     * início da seleção, no meio da linha onde ele está. Referência do arraste da alça inicial (c03b). Falha se
     * nada estiver selecionado.
     */
    fun pontoDoInicioDaSelecao(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val inicio = tv.selectionStart
            check(inicio >= 0) { "Nada selecionado no parágrafo $indiceParagrafo" }
            val linha = layout.getLineForOffset(inicio)
            val x = layout.getPrimaryHorizontal(inicio)
            val y = (layout.getLineTop(linha) + layout.getLineBottom(linha)) / 2f
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para o início da seleção do parágrafo $indiceParagrafo" }
    }

    /**
     * Ponto da ponta da seleção do parágrafo [indiceParagrafo], em pixels de tela: a posição horizontal do fim da
     * seleção, no meio da linha onde ele está. É a referência do arraste relativo do C03: a distância entre o
     * alvo e este ponto é quanto o dedo tem de andar. Falha se nada estiver selecionado.
     */
    fun pontoDoFimDaSelecao(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int): PointF {
        var ponto: PointF? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val fim = tv.selectionEnd
            check(fim >= 0) { "Nada selecionado no parágrafo $indiceParagrafo" }
            val linha = layout.getLineForOffset(fim)
            val x = layout.getPrimaryHorizontal(fim)
            val y = (layout.getLineTop(linha) + layout.getLineBottom(linha)) / 2f
            ponto = naTela(tv, x, y)
        }
        return checkNotNull(ponto) { "Sem ponto para o fim da seleção do parágrafo $indiceParagrafo" }
    }

    /**
     * Altura, em pixels, da linha onde termina a seleção do parágrafo [indiceParagrafo]. Falha se nada estiver
     * selecionado.
     */
    fun alturaDaLinha(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int): Float {
        var altura: Float? = null
        scenario.onActivity { a ->
            val tv = textoDoParagrafo(a, indiceParagrafo)
            val layout = tv.layout ?: error("Parágrafo $indiceParagrafo sem layout")
            val fim = tv.selectionEnd
            check(fim >= 0) { "Nada selecionado no parágrafo $indiceParagrafo" }
            val linha = layout.getLineForOffset(fim)
            altura = (layout.getLineBottom(linha) - layout.getLineTop(linha)).toFloat()
        }
        return checkNotNull(altura) { "Sem altura de linha para o parágrafo $indiceParagrafo" }
    }

    /**
     * O TextView do parágrafo na rolagem vertical (o item é o próprio TextView). Só na thread principal. Falha se o
     * parágrafo não estiver na tela.
     */
    fun textoDoParagrafo(a: ReaderActivity, indiceParagrafo: Int): TextView {
        val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
        return lista.findViewHolderForAdapterPosition(indiceParagrafo)?.itemView as? TextView
            ?: error("Parágrafo $indiceParagrafo fora da tela")
    }

    /** Converte uma coordenada do layout do TextView (sem padding nem rolagem) para pixels de tela. */
    private fun naTela(tv: TextView, x: Float, y: Float): PointF {
        val posicao = IntArray(2)
        tv.getLocationOnScreen(posicao)
        return PointF(
            posicao[0] + tv.totalPaddingLeft - tv.scrollX + x,
            posicao[1] + tv.totalPaddingTop - tv.scrollY + y
        )
    }

    /** Injeta um toque de um dedo (SOURCE_TOUCHSCREEN) e libera o evento. Falha se o sistema recusar. */
    private fun injetar(acao: Int, inicio: Long, tempo: Long, x: Float, y: Float) {
        val propriedade = MotionEvent.PointerProperties()
        propriedade.id = 0
        propriedade.toolType = MotionEvent.TOOL_TYPE_FINGER
        val coordenada = MotionEvent.PointerCoords()
        coordenada.x = x
        coordenada.y = y
        coordenada.pressure = 1f
        coordenada.size = 1f
        val evento = MotionEvent.obtain(
            inicio, tempo, acao, 1,
            arrayOf(propriedade), arrayOf(coordenada),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(evento, true)) {
                "O sistema recusou o toque em ($x, $y)"
            }
        } finally {
            evento.recycle()
        }
    }

    private fun checarThreadDoTeste() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Gestos devem rodar na thread do teste, não na principal"
        }
    }
}
