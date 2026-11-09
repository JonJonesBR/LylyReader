package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanoDeAntecipacaoTest {

    @Test
    fun semNenhumParagrafoPreparadoPreparaMesmoComMuitoAudio() {
        assertTrue(PlanoDeAntecipacao.devePreparar(99f, 0))
    }

    @Test
    fun umPreparadoComPoucoAudioPreparaMais() {
        assertTrue(PlanoDeAntecipacao.devePreparar(3f, 1))
    }

    @Test
    fun umPreparadoComOrcamentoCheioNaoPreparaMais() {
        assertFalse(PlanoDeAntecipacao.devePreparar(25f, 1))
    }

    @Test
    fun quatroPreparadosNaoPreparamMaisMesmoComPoucoAudio() {
        assertFalse(PlanoDeAntecipacao.devePreparar(5f, 4))
    }

    @Test
    fun tresPreparadosComAudioAbaixoDoOrcamentoPreparamMais() {
        assertTrue(PlanoDeAntecipacao.devePreparar(24.9f, 3))
    }

    @Test
    fun tresPreparadosNoOrcamentoNaoPreparamMais() {
        assertFalse(PlanoDeAntecipacao.devePreparar(25f, 3))
    }

    @Test
    fun estimativaPorLetrasUsaDezLetrasPorSegundo() {
        assertEquals(10f, PlanoDeAntecipacao.estimarSegundosPorLetras(100), 0.0001f)
        assertEquals(0f, PlanoDeAntecipacao.estimarSegundosPorLetras(0), 0.0001f)
    }
}
