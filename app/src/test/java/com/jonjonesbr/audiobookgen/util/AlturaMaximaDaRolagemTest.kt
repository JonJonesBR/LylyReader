package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AlturaMaximaDaRolagemTest {

    @Test fun retratoDeTelaAltaDeixaA60PorCentoDaAltura() {
        // Tela de 800 dp: a lista pode ocupar 480 dp, não mais.
        assertEquals(480, alturaMaximaDaRolagem(800))
    }

    @Test fun paisagemDeTelaBaixaFicaComAFracaoDaAltura() {
        // Paisagem de 360 dp: 216 dp de lista, e o título e os botões sobram na tela.
        assertEquals(216, alturaMaximaDaRolagem(360))
    }

    @Test fun arredondaAoPixelMaisProximo() {
        assertEquals(1, alturaMaximaDaRolagem(2))
        assertEquals(0, alturaMaximaDaRolagem(0))
    }
}
