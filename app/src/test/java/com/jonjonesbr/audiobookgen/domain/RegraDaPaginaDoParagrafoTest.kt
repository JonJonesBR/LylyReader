package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RegraDaPaginaDoParagrafoTest {

    // Altura visível de 1000 px: a linha de destaque fica em 420 px (42%).
    private val altura = 1000

    @Test
    fun alturaZeroNaoRola() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(900, 0))
    }

    @Test
    fun linhaAbaixoDoAlvoMasNoLimiarNaoRola() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(300, altura))
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(426, altura))
    }

    @Test
    fun logoAcimaDoLimiarRolaOPassoMinimo() {
        assertEquals(1, RegraDaPaginaDoParagrafo.passoParaDestaque(427, altura))
    }

    @Test
    fun linhaDentroDaAreaRolaEmPasso() {
        assertEquals(90, RegraDaPaginaDoParagrafo.passoParaDestaque(920, altura))
    }

    @Test
    fun linhaForaDaAreaVaiDireto() {
        assertEquals(1080, RegraDaPaginaDoParagrafo.passoParaDestaque(1500, altura))
    }

    @Test
    fun linhaAcimaDoTopoVoltaDireto() {
        assertEquals(-620, RegraDaPaginaDoParagrafo.passoParaDestaque(-200, altura))
    }
}
