package com.jonjonesbr.audiobookgen.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonecaTrocaDeLivroTest {

    @After
    fun limpaASoneca() {
        SleepTimerManager.cancel()
    }

    // --- Função pura: quando a troca de livro pergunta ---

    @Test
    fun semSonecaAtivaNaoPergunta() {
        assertFalse(sonecaPedeConfirmacaoNaTroca(false, 1L, 2L))
    }

    @Test
    fun mesmaSessaoNaoPergunta() {
        assertFalse(sonecaPedeConfirmacaoNaTroca(true, 2L, 2L))
    }

    @Test
    fun outraSessaoPergunta() {
        assertTrue(sonecaPedeConfirmacaoNaTroca(true, 1L, 2L))
    }

    @Test
    fun sonecaDoAudiobookPergunta() {
        assertTrue(sonecaPedeConfirmacaoNaTroca(true, null, 2L))
    }

    // --- SleepTimerManager: re-atrelar à sessão nova e confirmar ---

    @Test
    fun assumirSessaoNovaReatrelaSemMudarOModo() {
        SleepTimerManager.armarFimDoCapitulo(1L)

        SleepTimerManager.assumirSessaoNova(2L)

        assertEquals(2L, SleepTimerManager.armedToken)
        assertEquals(2L, SleepTimerManager.confirmacaoTrocaPendente.value)
        assertTrue(SleepTimerManager.fimDoCapitulo.value)
        assertTrue(SleepTimerManager.isActive)
    }

    @Test
    fun confirmarSessaoNovaLimpaAPendenciaMantendoASoneca() {
        SleepTimerManager.armarFimDoCapitulo(1L)
        SleepTimerManager.assumirSessaoNova(2L)

        SleepTimerManager.confirmarSessaoNova()

        assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)
        assertTrue(SleepTimerManager.isActive)
        assertEquals(2L, SleepTimerManager.armedToken)
    }

    @Test
    fun assumirSessaoNovaSemSonecaAtivaNaoFazNada() {
        SleepTimerManager.assumirSessaoNova(2L)

        assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)
        assertNull(SleepTimerManager.armedToken)
        assertFalse(SleepTimerManager.isActive)
    }

    @Test
    fun cancelLimpaAPendencia() {
        SleepTimerManager.armarFimDoCapitulo(1L)
        SleepTimerManager.assumirSessaoNova(2L)

        SleepTimerManager.cancel()

        assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)
        assertFalse(SleepTimerManager.isActive)
    }
}
