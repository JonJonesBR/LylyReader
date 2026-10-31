package com.jonjonesbr.audiobookgen.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonecaFimDoCapituloTest {

    @After
    fun limpaASoneca() {
        SleepTimerManager.cancel()
    }

    // --- Função pura: quando a soneca de capítulo dispara ---

    @Test
    fun disparaNoUltimoParagrafoDoCapitulo() {
        assertTrue(sonecaDeCapituloDispara(true, 7L, 7L, 0, 1))
    }

    @Test
    fun naoDisparaNoMeioDoCapitulo() {
        assertFalse(sonecaDeCapituloDispara(true, 7L, 7L, 0, 0))
    }

    @Test
    fun disparaNoFimDoLivro() {
        assertTrue(sonecaDeCapituloDispara(true, 7L, 7L, 3, null))
    }

    @Test
    fun desarmadaNaoDispara() {
        assertFalse(sonecaDeCapituloDispara(false, 7L, 7L, 0, 1))
    }

    @Test
    fun tokenDeOutraSessaoNaoDispara() {
        assertFalse(sonecaDeCapituloDispara(true, 8L, 7L, 0, 1))
    }

    @Test
    fun semTokenArmadoNaoDispara() {
        assertFalse(sonecaDeCapituloDispara(true, null, 7L, 0, 1))
    }

    @Test
    fun semCapituloAtualNaoDispara() {
        assertFalse(sonecaDeCapituloDispara(true, 7L, 7L, null, 1))
    }

    // --- Função pura: modo da soneca ---

    @Test
    fun modoDaSonecaDesligadaSemContagemNemFimDoCapitulo() {
        assertEquals(ModoSoneca.DESLIGADA, modoDaSoneca(null, false))
    }

    @Test
    fun modoDaSonecaContagemComRestante() {
        assertEquals(ModoSoneca.CONTAGEM, modoDaSoneca(60_000L, false))
    }

    @Test
    fun modoDaSonecaFimDoCapituloSemContagem() {
        assertEquals(ModoSoneca.FIM_DO_CAPITULO, modoDaSoneca(null, true))
    }

    @Test
    fun modoDaSonecaFimDoCapituloTemPrecedenciaSobreContagem() {
        assertEquals(ModoSoneca.FIM_DO_CAPITULO, modoDaSoneca(60_000L, true))
    }

    // --- SleepTimerManager: armar, disparar e cancelar ---

    @Test
    fun armarFimDoCapituloLigaOModoSemContagemNemFade() {
        SleepTimerManager.armarFimDoCapitulo(7L)

        assertTrue(SleepTimerManager.isActive)
        assertTrue(SleepTimerManager.fimDoCapitulo.value)
        assertEquals(7L, SleepTimerManager.armedToken)
        assertNull(SleepTimerManager.remainingMs.value)
        assertNull(SleepTimerManager.armedMinutes.value)
        assertEquals(1f, SleepTimerManager.volumeFactor.value, 0.001f)
    }

    @Test
    fun dispararDeOutraSessaoNaoDesarma() {
        SleepTimerManager.armarFimDoCapitulo(7L)

        assertFalse(SleepTimerManager.dispararFimDoCapitulo(8L))
        assertTrue(SleepTimerManager.fimDoCapitulo.value)
        assertEquals(7L, SleepTimerManager.armedToken)
    }

    @Test
    fun dispararDaSessaoArmadaDesarmaEMantemOToken() {
        SleepTimerManager.armarFimDoCapitulo(7L)

        assertTrue(SleepTimerManager.dispararFimDoCapitulo(7L))
        assertFalse(SleepTimerManager.isActive)
        assertFalse(SleepTimerManager.fimDoCapitulo.value)
        assertEquals(7L, SleepTimerManager.armedToken)
    }

    @Test
    fun cancelLimpaTodaASoneca() {
        SleepTimerManager.armarFimDoCapitulo(7L)

        SleepTimerManager.cancel()

        assertFalse(SleepTimerManager.isActive)
        assertFalse(SleepTimerManager.fimDoCapitulo.value)
        assertNull(SleepTimerManager.armedToken)
        assertNull(SleepTimerManager.remainingMs.value)
    }
}
