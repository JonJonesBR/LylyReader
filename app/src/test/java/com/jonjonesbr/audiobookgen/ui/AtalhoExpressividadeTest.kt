package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AtalhoExpressividadeTest {

    // Atalhos do Pocket TTS em décimos: Padrão (1), Moderada (3), Expressiva (7), Máxima (10).
    private val atalhos = listOf(1, 3, 7, 10)

    @Test
    fun valorDeAtalhoFicaNoProprioAtalho() {
        assertEquals(1, atalhoExpressividadeMaisProximo(1, atalhos))
        assertEquals(3, atalhoExpressividadeMaisProximo(3, atalhos))
        assertEquals(7, atalhoExpressividadeMaisProximo(7, atalhos))
        assertEquals(10, atalhoExpressividadeMaisProximo(10, atalhos))
    }

    @Test
    fun valorSemAtalhoMarcaOMaisProximo() {
        assertEquals(3, atalhoExpressividadeMaisProximo(4, atalhos))
        assertEquals(7, atalhoExpressividadeMaisProximo(6, atalhos))
        assertEquals(7, atalhoExpressividadeMaisProximo(8, atalhos))
        assertEquals(10, atalhoExpressividadeMaisProximo(9, atalhos))
    }

    @Test
    fun noEmpateValeOMenorAtalho() {
        // 2 está a 1 de Padrão e de Moderada; 5 está a 2 de Moderada e de Expressiva.
        assertEquals(1, atalhoExpressividadeMaisProximo(2, atalhos))
        assertEquals(3, atalhoExpressividadeMaisProximo(5, atalhos))
    }
}
