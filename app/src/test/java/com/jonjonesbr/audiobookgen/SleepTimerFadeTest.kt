package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.service.calcularFatorVolumeFade
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerFadeTest {

    private val umMinuto = 60_000L

    @Test fun volumeCheioForaDaJanelaDeFade() {
        assertEquals(1f, calcularFatorVolumeFade(remainingMs = umMinuto, totalMs = 10 * umMinuto), 0.001f)
    }

    @Test fun volumeCaiPelaMetadeNoMeioDaJanela() {
        assertEquals(
            0.5f,
            calcularFatorVolumeFade(remainingMs = 15_000, totalMs = 10 * umMinuto, fadeDurationMs = 30_000),
            0.001f
        )
    }

    @Test fun volumeZeroQuandoAcaba() {
        assertEquals(0f, calcularFatorVolumeFade(remainingMs = 0, totalMs = 10 * umMinuto), 0.001f)
    }

    @Test fun janelaDeFadeLimitadaPelaDuracaoTotalDaSoneca() {
        // Soneca de 10s é mais curta que os 30s de fade padrão: a janela vira 10s inteiros.
        assertEquals(
            0.5f,
            calcularFatorVolumeFade(remainingMs = 5_000, totalMs = 10_000, fadeDurationMs = 30_000),
            0.001f
        )
    }

    @Test fun volumeCheioNoInicioDeUmaSonecaCurta() {
        assertEquals(1f, calcularFatorVolumeFade(remainingMs = 10_000, totalMs = 10_000), 0.001f)
    }
}
