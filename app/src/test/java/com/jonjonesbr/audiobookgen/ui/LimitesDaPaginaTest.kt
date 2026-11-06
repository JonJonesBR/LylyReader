package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitesDaPaginaTest {

    @Test
    fun linhaAcimaDeCMaiorQueAPaginaNaoGuardaLimite() {
        // Q é a própria C (a volta só deslocou o começo do texto): distância zero não pode travar a página.
        assertNull("sem distância positiva a volta não guarda limite", distanciaDoLimite(100, 100))
        // Q abaixo de C na tela: C não ficou abaixo de Q, então também não há o que limitar.
        assertNull(distanciaDoLimite(100, 140))
    }

    @Test
    fun distanciaPositivaFicaGuardada() {
        // C ficou 200 px abaixo de Q: a página da volta termina antes de C.
        assertEquals(200, distanciaDoLimite(300, 100))
    }
}
