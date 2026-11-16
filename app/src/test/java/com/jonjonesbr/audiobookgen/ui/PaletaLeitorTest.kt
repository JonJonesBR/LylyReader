package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("MagicNumber")
class PaletaLeitorTest {

    @Test
    fun paletaClaroMantemTodasAsCoresEsperadas() {
        val paleta = paletaDoLeitor(TemaLeitura.CLARO)
        assertEquals(0xFFF8F8F8.toInt(), paleta.fundo)
        assertEquals(0xFFECECEC.toInt(), paleta.superficie)
        assertEquals(0xFF222222.toInt(), paleta.texto)
        assertEquals(0xFF5F5F5F.toInt(), paleta.suave)
        assertTrue(paleta.clara)
        assertEquals(0xFF333333.toInt(), paleta.textoParagrafo)
        assertEquals(0xFFD0C0FF.toInt(), paleta.selecao)
        assertEquals(0xFFFFE082.toInt(), paleta.buscaAtual)
        assertEquals(0xFFFFF8E1.toInt(), paleta.buscaMatch)
        assertEquals(0xFFDAD2F5.toInt(), paleta.sync)
        assertEquals(0xFF6A4CA0.toInt(), paleta.barraSync)
    }

    @Test
    fun paletaEscuroMantemTodasAsCoresEsperadas() {
        val paleta = paletaDoLeitor(TemaLeitura.ESCURO)
        assertEquals(0xFF1A1A1A.toInt(), paleta.fundo)
        assertEquals(0xFF262626.toInt(), paleta.superficie)
        assertEquals(0xFFE6E6E6.toInt(), paleta.texto)
        assertEquals(0xFFA0A0A0.toInt(), paleta.suave)
        assertFalse(paleta.clara)
        assertEquals(0xFFE0E0E0.toInt(), paleta.textoParagrafo)
        assertEquals(0xFF4A3070.toInt(), paleta.selecao)
        assertEquals(0xFF5C4A20.toInt(), paleta.buscaAtual)
        assertEquals(0xFF3A3020.toInt(), paleta.buscaMatch)
        assertEquals(0xFF2A2040.toInt(), paleta.sync)
        assertEquals(0xFFB39DDB.toInt(), paleta.barraSync)
    }

    @Test
    fun paletaSepiaMantemTodasAsCoresEsperadas() {
        val paleta = paletaDoLeitor(TemaLeitura.SEPIA)
        assertEquals(0xFFF5ECD7.toInt(), paleta.fundo)
        assertEquals(0xFFEADFC4.toInt(), paleta.superficie)
        assertEquals(0xFF3B2F1E.toInt(), paleta.texto)
        assertEquals(0xFF7A6A50.toInt(), paleta.suave)
        assertTrue(paleta.clara)
        assertEquals(0xFF3B2A1A.toInt(), paleta.textoParagrafo)
        assertEquals(0xFFC8A870.toInt(), paleta.selecao)
        assertEquals(0xFFD4A040.toInt(), paleta.buscaAtual)
        assertEquals(0xFFE8D0A0.toInt(), paleta.buscaMatch)
        assertEquals(0xFFDFC99C.toInt(), paleta.sync)
        assertEquals(0xFF6B4423.toInt(), paleta.barraSync)
    }

    @Test
    fun paletaOledMantemTodasAsCoresEsperadas() {
        val paleta = paletaDoLeitor(TemaLeitura.OLED)
        assertEquals(0xFF000000.toInt(), paleta.fundo)
        assertEquals(0xFF141414.toInt(), paleta.superficie)
        assertEquals(0xFFE0E0E0.toInt(), paleta.texto)
        assertEquals(0xFF9A9A9A.toInt(), paleta.suave)
        assertFalse(paleta.clara)
        assertEquals(0xFFCCCCCC.toInt(), paleta.textoParagrafo)
        assertEquals(0xFF4A3070.toInt(), paleta.selecao)
        assertEquals(0xFF5C4A20.toInt(), paleta.buscaAtual)
        assertEquals(0xFF3A3020.toInt(), paleta.buscaMatch)
        assertEquals(0xFF2A2040.toInt(), paleta.sync)
        assertEquals(0xFFB39DDB.toInt(), paleta.barraSync)
    }

    @Test
    fun constantesDeDestaquesMantemValoresEsperados() {
        assertEquals(0x55FFC107, COR_DESTAQUE_PERSISTENTE)
        assertEquals(0xCCFF9800.toInt(), COR_TRECHO_BUSCA_ATUAL)
        assertEquals(0x88FFEB3B.toInt(), COR_TRECHO_BUSCA)
        assertEquals(0xFFB71C1C.toInt(), COR_PARAGRAFO_REMOVIDO)
    }
}
