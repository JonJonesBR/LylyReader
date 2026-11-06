package com.jonjonesbr.audiobookgen.domain

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** rb-6: regra pura da rolagem do leitor (ver RegraDaRolagemDoLeitor). Sem Android: só as constantes de KeyEvent. */
class RegraDaRolagemDoLeitorTest {

    // ── Quem conduz a rolagem ─────────────────────────────────────────────────

    @Test
    fun manualTiraAConducao() {
        assertFalse(RegraDaRolagemDoLeitor.guiadaConduzRolagem(3) { true })
        assertTrue(RegraDaRolagemDoLeitor.guiadaConduzRolagem(3) { false })
    }

    @Test
    fun semGuiadaNaoConduz() {
        // Sem leitura guiada ativa, a barra nem é consultada.
        assertFalse(RegraDaRolagemDoLeitor.guiadaConduzRolagem(-1) { fail(); true })
    }

    @Test
    fun audiobookSuspensoNaoRola() {
        assertFalse(RegraDaRolagemDoLeitor.podeRolarComAudiobook(agora = 100, suspensaAte = 200, true, 5, -1))
        assertTrue(RegraDaRolagemDoLeitor.podeRolarComAudiobook(agora = 200, suspensaAte = 200, true, 5, -1))
    }

    @Test
    fun mesmoParagrafoNaoRolaDeNovo() {
        assertFalse(RegraDaRolagemDoLeitor.podeRolarComAudiobook(300, 0, true, 5, 5))
    }

    @Test
    fun audiobookSemSyncNaoRola() {
        assertFalse(RegraDaRolagemDoLeitor.podeRolarComAudiobook(300, 0, false, 5, -1))
        assertFalse(RegraDaRolagemDoLeitor.podeRolarComAudiobook(300, 0, true, -1, -1))
    }

    // ── Referência para progresso e marcadores ────────────────────────────────

    @Test
    fun semTextoNaoHaReferencia() {
        assertNull(RegraDaRolagemDoLeitor.paragrafoDeReferencia(false, 3, 2))
    }

    @Test
    fun syncVenceATela() {
        assertEquals(5, RegraDaRolagemDoLeitor.paragrafoDeReferencia(true, 5, 2))
    }

    @Test
    fun semSyncUsaOPrimeiroVisivel() {
        assertEquals(2, RegraDaRolagemDoLeitor.paragrafoDeReferencia(true, -1, 2))
    }

    @Test
    fun semSyncENemVisivelNaoHaReferencia() {
        assertNull(RegraDaRolagemDoLeitor.paragrafoDeReferencia(true, -1, -1))
        assertNull(RegraDaRolagemDoLeitor.paragrafoDeReferencia(true, -1, null))
    }

    // ── Posição a gravar ao sair ──────────────────────────────────────────────

    @Test
    fun guiadaEncerradaVenceATela() {
        assertEquals(4, RegraDaRolagemDoLeitor.paragrafoParaSalvar(4, -1, true, 10, 2))
    }

    @Test
    fun syncAtivoIgnoraGuiadaEncerrada() {
        assertEquals(6, RegraDaRolagemDoLeitor.paragrafoParaSalvar(4, 6, true, 10, 6))
    }

    @Test
    fun fimDoLivroSalvaOUltimo() {
        assertEquals(9, RegraDaRolagemDoLeitor.paragrafoParaSalvar(-1, -1, true, 10, 3))
    }

    @Test
    fun semFimSalvaOVisivel() {
        assertEquals(3, RegraDaRolagemDoLeitor.paragrafoParaSalvar(-1, -1, false, 10, 3))
    }

    // ── Posição a restaurar ao abrir ──────────────────────────────────────────

    @Test
    fun reconexaoVenceAPosicaoSalva() {
        assertEquals(4, RegraDaRolagemDoLeitor.posicaoARestaurar(true, 4, false, 7, 10))
    }

    @Test
    fun reconexaoForaDoTextoUsaAPosicaoSalva() {
        assertEquals(7, RegraDaRolagemDoLeitor.posicaoARestaurar(true, 12, false, 7, 10))
    }

    @Test
    fun posicaoSalvaRestaura() {
        assertEquals(7, RegraDaRolagemDoLeitor.posicaoARestaurar(false, -1, false, 7, 10))
    }

    @Test
    fun posicaoZeroNaoRola() {
        assertNull(RegraDaRolagemDoLeitor.posicaoARestaurar(false, -1, false, 0, 10))
    }

    @Test
    fun inicioAutomaticoNaoRestaura() {
        assertNull(RegraDaRolagemDoLeitor.posicaoARestaurar(false, -1, true, 7, 10))
    }

    // ── Teclas ────────────────────────────────────────────────────────────────

    @Test
    fun teclasDePaginaDaoOSentido() {
        assertEquals(-1, RegraDaRolagemDoLeitor.sentidoDaTecla(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(1, RegraDaRolagemDoLeitor.sentidoDaTecla(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun outrasTeclasNaoRolam() {
        assertNull(RegraDaRolagemDoLeitor.sentidoDaTecla(KeyEvent.KEYCODE_BACK))
        assertFalse(RegraDaRolagemDoLeitor.teclaDeRolagem(KeyEvent.KEYCODE_BACK))
        assertFalse(RegraDaRolagemDoLeitor.teclaDeRolagem(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun setasVerticaisRolam() {
        assertTrue(RegraDaRolagemDoLeitor.teclaDeRolagem(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(RegraDaRolagemDoLeitor.teclaDeRolagem(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(RegraDaRolagemDoLeitor.ehSetaVertical(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(RegraDaRolagemDoLeitor.ehSetaVertical(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun sentidoDaSetaVemDaTecla() {
        assertEquals(1, RegraDaRolagemDoLeitor.sentidoDaSeta(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(-1, RegraDaRolagemDoLeitor.sentidoDaSeta(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(RegraDaRolagemDoLeitor.sentidoDaSeta(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(RegraDaRolagemDoLeitor.sentidoDaSeta(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun setaRolaAPaginaSoComTextoFocadoSemSelecao() {
        assertTrue(setaNaPagina(shift = false, selecao = false, focado = true, rola = true))
    }

    @Test
    fun setaNaoRolaAPaginaComShiftOuSelecao() {
        assertFalse(setaNaPagina(shift = true, selecao = false, focado = true, rola = true))
        assertFalse(setaNaPagina(shift = false, selecao = true, focado = true, rola = true))
    }

    @Test
    fun setaNaoRolaAPaginaSemFocoOuSemTextoParaRolar() {
        assertFalse(setaNaPagina(shift = false, selecao = false, focado = false, rola = true))
        assertFalse(setaNaPagina(shift = false, selecao = false, focado = true, rola = false))
    }

    // ── Passo da tecla de página (rb-67/13) ──────────────────────────────────

    @Test
    fun paginaAndaAlturaUtilMenosUmaLinha() {
        // Altura 1000, paddings 50 e 200: a útil é 750; menos a linha de 40, sobram 710.
        assertEquals(710, passoDaTeclaDePagina(1000, 50, 200, 40))
    }

    @Test
    fun paginaSemLinhasAndaAlturaUtilInteira() {
        assertEquals(750, passoDaTeclaDePagina(1000, 50, 200, 0))
    }

    @Test
    fun paginaNuncaAndaMenosQueUmaLinha() {
        // Útil negativa (padding maior que a lista): anda pelo menos uma linha, para não travar a leitura.
        assertEquals(40, passoDaTeclaDePagina(100, 0, 90, 40))
    }

    private fun setaNaPagina(shift: Boolean, selecao: Boolean, focado: Boolean, rola: Boolean): Boolean =
        RegraDaRolagemDoLeitor.setaRolaAPagina(
            shift = shift,
            temSelecao = selecao,
            textoFocado = focado,
            podeRolar = rola,
        )
}
