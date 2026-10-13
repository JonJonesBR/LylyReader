package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiteDeProgressoTest {

    @Test
    fun primeiroProgressoAtualiza() {
        assertTrue(LimiteDeProgresso.deveAtualizar(null, 1000L, 50))
    }

    @Test
    fun antesDeUmSegundoNaoAtualiza() {
        assertFalse(LimiteDeProgresso.deveAtualizar(0L, 999L, 50))
    }

    @Test
    fun exatamenteUmSegundoAtualiza() {
        assertTrue(LimiteDeProgresso.deveAtualizar(0L, 1000L, 50))
    }

    @Test
    fun pctZeroSempreAtualiza() {
        assertTrue(LimiteDeProgresso.deveAtualizar(0L, 400L, 0))
    }

    @Test
    fun pct95SempreAtualiza() {
        assertTrue(LimiteDeProgresso.deveAtualizar(0L, 400L, 95))
    }

    @Test
    fun pct100SempreAtualiza() {
        assertTrue(LimiteDeProgresso.deveAtualizar(0L, 400L, 100))
    }

    @Test
    fun pct50A400MsNaoAtualiza() {
        assertFalse(LimiteDeProgresso.deveAtualizar(0L, 400L, 50))
    }

    @Test
    fun intervaloCustomizadoRespeitaOLimite() {
        assertFalse(LimiteDeProgresso.deveAtualizar(1000L, 2999L, 50, intervaloMs = 2000L))
        assertTrue(LimiteDeProgresso.deveAtualizar(1000L, 3000L, 50, intervaloMs = 2000L))
    }
}
