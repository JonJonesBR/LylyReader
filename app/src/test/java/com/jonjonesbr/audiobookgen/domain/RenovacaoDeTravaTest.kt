package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenovacaoDeTravaTest {

    private val intervalo = 5 * 60_000L

    @Test
    fun antesDoIntervaloNaoRenova() {
        assertFalse(RenovacaoDeTrava.deveRenovar(0L, intervalo - 1L))
    }

    @Test
    fun exatamenteNoIntervaloRenova() {
        assertTrue(RenovacaoDeTrava.deveRenovar(0L, intervalo))
    }

    @Test
    fun depoisDoIntervaloRenova() {
        assertTrue(RenovacaoDeTrava.deveRenovar(0L, intervalo + 60_000L))
    }

    @Test
    fun intervaloCustomizadoRespeitaOLimite() {
        assertFalse(RenovacaoDeTrava.deveRenovar(1000L, 2999L, intervaloMs = 2000L))
        assertTrue(RenovacaoDeTrava.deveRenovar(1000L, 3000L, intervaloMs = 2000L))
    }
}
