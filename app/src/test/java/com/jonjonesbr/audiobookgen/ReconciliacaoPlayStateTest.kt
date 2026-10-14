package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.ehPlayPauseExterno
import com.jonjonesbr.audiobookgen.domain.reconciliarIsPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliacaoPlayStateTest {

    @Test
    fun playExternoLigaEstadoQuandoEstavaParado() {
        // Play pela notificação/fone: playWhenReady vira true, estado ainda false.
        assertEquals(true, reconciliarIsPlaying(false, true, false, false))
    }

    @Test
    fun pausaExternaDesligaEstadoQuandoEstavaTocando() {
        assertEquals(false, reconciliarIsPlaying(true, false, false, false))
    }

    @Test
    fun bufferingComPlayWhenReadyVerdadeiroNaoDerrubaEstado() {
        // BUFFERING: isPlaying seria false, mas a intenção continua true. Nada muda.
        assertNull(reconciliarIsPlaying(true, true, false, false))
    }

    @Test
    fun fimDoLivroNaoViraTocandoMesmoComPlayWhenReadyVerdadeiro() {
        // STATE_ENDED pode manter playWhenReady=true; o estado deve ficar parado.
        assertNull(reconciliarIsPlaying(false, true, true, false))
    }

    @Test
    fun fimDoLivroDerrubaEstadoQueAindaEstavaTocando() {
        assertEquals(false, reconciliarIsPlaying(true, true, true, false))
    }

    @Test
    fun caminhoVazioDevolveNulo() {
        assertNull(reconciliarIsPlaying(false, true, false, true))
        assertNull(reconciliarIsPlaying(true, false, false, true))
    }

    @Test
    fun valorIgualAoAtualDevolveNulo() {
        assertNull(reconciliarIsPlaying(false, false, false, false))
        assertNull(reconciliarIsPlaying(true, true, false, false))
    }

    @Test
    fun mudancaVindaDeForaComLivroCarregadoEhExterna() {
        assertTrue(ehPlayPauseExterno(mudancaInterna = false, playbackEnded = false, caminhoVazio = false))
    }

    @Test
    fun mudancaIniciadaPeloAppNaoEhExterna() {
        assertFalse(ehPlayPauseExterno(mudancaInterna = true, playbackEnded = false, caminhoVazio = false))
    }

    @Test
    fun fimDoLivroNaoEhPlayPauseExterno() {
        assertFalse(ehPlayPauseExterno(mudancaInterna = false, playbackEnded = true, caminhoVazio = false))
    }

    @Test
    fun semLivroCarregadoNaoEhPlayPauseExterno() {
        assertFalse(ehPlayPauseExterno(mudancaInterna = false, playbackEnded = false, caminhoVazio = true))
    }
}
