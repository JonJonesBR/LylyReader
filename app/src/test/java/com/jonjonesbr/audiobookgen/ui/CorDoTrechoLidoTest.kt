package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorDoTrechoLidoTest {

    @Test
    fun comAlfaTrocaSoOAlfa() {
        assertEquals(0x80123456.toInt(), comAlfa(0x123456, 0x80))
        // Cor com alfa já preenchido: o alfa antigo é substituído, não somado.
        assertEquals(0x40ABCDEF, comAlfa(0xFFABCDEF.toInt(), 0x40))
    }

    @Test
    fun comAlfaLimitaOAlfaAoIntervalo() {
        assertEquals(0xFFFFFFFF.toInt(), comAlfa(0xFFFFFF, 300))
        assertEquals(0x00ABCDEF, comAlfa(0xABCDEF, -5))
    }

    @Test
    fun trechoLidoEhTranslucidoNosQuatroTemas() {
        TemaLeitura.values().forEach { tema ->
            val alfa = corDoTrechoLido(tema) ushr 24
            assertTrue("alfa de $tema deve ser menor que 255 para a seleção aparecer", alfa < 0xFF)
            assertTrue("alfa de $tema deve manter o trecho lido distinto", alfa >= 0x80)
        }
    }

    @Test
    fun trechoLidoMantemAsCoresDoTema() {
        assertEquals(0xB3BBA6FF.toInt(), corDoTrechoLido(TemaLeitura.CLARO))
        assertEquals(0xB36A4CA0.toInt(), corDoTrechoLido(TemaLeitura.ESCURO))
        assertEquals(0xB3D8B880.toInt(), corDoTrechoLido(TemaLeitura.SEPIA))
        assertEquals(0xB36A4CA0.toInt(), corDoTrechoLido(TemaLeitura.OLED))
    }
}
