package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class VelocidadesGuiadasTest {

    private val portugues = Locale.forLanguageTag("pt-BR")
    private val espanhol = Locale.forLanguageTag("es-ES")
    private val ingles = Locale.US

    @Test fun rotuloUsaVirgulaEmPortugues() {
        assertEquals("0,75×", rotuloVelocidade(0.75f, portugues))
        assertEquals("1,25×", rotuloVelocidade(1.25f, portugues))
    }

    @Test fun rotuloUsaVirgulaEmEspanhol() {
        assertEquals("1,75×", rotuloVelocidade(1.75f, espanhol))
    }

    @Test fun rotuloUsaPontoEmIngles() {
        assertEquals("0.75×", rotuloVelocidade(0.75f, ingles))
        assertEquals("1.75×", rotuloVelocidade(1.75f, ingles))
    }

    @Test fun rotuloSemZerosInuteis() {
        assertEquals("1×", rotuloVelocidade(1.0f, portugues))
        assertEquals("2×", rotuloVelocidade(2.0f, ingles))
        assertEquals("1,5×", rotuloVelocidade(1.5f, portugues))
        assertEquals("1.5×", rotuloVelocidade(1.5f, ingles))
    }

    @Test fun presetMaisProximoDeValorLivre() {
        assertEquals(1.0f, velocidadeMaisProxima(1.1f), 0.001f)
        assertEquals(1.25f, velocidadeMaisProxima(1.2f), 0.001f)
        assertEquals(0.75f, velocidadeMaisProxima(0.6f), 0.001f)
    }

    @Test fun valorForaDaFaixaVaiParaOExtremo() {
        assertEquals(2.0f, velocidadeMaisProxima(3.0f), 0.001f)
        assertEquals(0.75f, velocidadeMaisProxima(0.1f), 0.001f)
    }
}
