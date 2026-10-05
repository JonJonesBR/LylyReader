package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.TtsRate
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsRateTest {

    @Test fun base100ehZero() {
        assertEquals("+0%", TtsRate.fromRitmo(100))
    }

    @Test fun acimaDe100ehPositivo() {
        assertEquals("+10%", TtsRate.fromRitmo(110))
        assertEquals("+50%", TtsRate.fromRitmo(150))
    }

    @Test fun abaixoDe100ehNegativo() {
        assertEquals("-10%", TtsRate.fromRitmo(90))
        // Regressao do bug corrigido: o default canonico 90 e o antigo 87.
        assertEquals("-13%", TtsRate.fromRitmo(87))
    }
}
