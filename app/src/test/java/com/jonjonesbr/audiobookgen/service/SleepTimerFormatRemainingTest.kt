package com.jonjonesbr.audiobookgen.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SleepTimerFormatRemainingTest {

    private var localePadrao: Locale? = null

    @Before fun fixaLocale() {
        // formatRemaining usa String.format (locale padrão): fixa en-US para o teste ser determinístico.
        localePadrao = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restauraLocale() {
        localePadrao?.let { Locale.setDefault(it) }
    }

    @Test fun zeroViraZeroSegundos() {
        assertEquals("0:00", SleepTimerManager.formatRemaining(0L))
    }

    @Test fun negativoViraZeroSegundos() {
        assertEquals("0:00", SleepTimerManager.formatRemaining(-1L))
        assertEquals("0:00", SleepTimerManager.formatRemaining(-5_000L))
    }

    @Test fun menosDeUmSegundoViraZeroSegundos() {
        assertEquals("0:00", SleepTimerManager.formatRemaining(999L))
    }

    @Test fun umSegundoExato() {
        assertEquals("0:01", SleepTimerManager.formatRemaining(1_000L))
    }

    @Test fun cinquentaESegundos() {
        assertEquals("0:59", SleepTimerManager.formatRemaining(59_000L))
    }

    @Test fun umMinutoExato() {
        assertEquals("1:00", SleepTimerManager.formatRemaining(60_000L))
    }

    @Test fun cincoMinutosEMeioComSegundosComZeroAEsquerda() {
        assertEquals("5:05", SleepTimerManager.formatRemaining(305_000L))
    }

    @Test fun cinquentaENoveMinutosECinquentaENoveSegundos() {
        assertEquals("59:59", SleepTimerManager.formatRemaining(3_599_000L))
    }

    @Test fun milissegundosSobrandoNaoArredondam() {
        // 3599999 ms = 59 min 59,999 s: o restante fracionário é descartado.
        assertEquals("59:59", SleepTimerManager.formatRemaining(3_599_999L))
    }

    @Test fun umaHoraExataMostraHoras() {
        assertEquals("1:00:00", SleepTimerManager.formatRemaining(3_600_000L))
    }

    @Test fun umaHoraUmMinutoUmSegundo() {
        assertEquals("1:01:01", SleepTimerManager.formatRemaining(3_661_000L))
    }

    @Test fun noventaENoveHorasECinquentaENoveMinutosECinquentaENoveSegundos() {
        assertEquals("99:59:59", SleepTimerManager.formatRemaining(359_999_000L))
    }

    @Test fun milHorasExatas() {
        assertEquals("1000:00:00", SleepTimerManager.formatRemaining(3_600_000_000L))
    }
}
