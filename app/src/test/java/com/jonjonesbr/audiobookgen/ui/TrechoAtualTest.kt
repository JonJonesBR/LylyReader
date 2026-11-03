package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrechoAtualTest {

    @Test
    fun guiadaVenceATela() {
        assertEquals(2, indiceDoTrechoAtual(indiceGuiada = 2, naTela = { 0 }))
    }

    @Test
    fun semGuiadaUsaATela() {
        assertEquals(1, indiceDoTrechoAtual(indiceGuiada = -1, naTela = { 1 }))
        assertEquals(1, indiceDoTrechoAtual(indiceGuiada = null, naTela = { 1 }))
    }

    @Test
    fun semNenhumDosDoisDaMenosUm() {
        assertEquals(-1, indiceDoTrechoAtual(indiceGuiada = null, naTela = { null }))
        assertEquals(-1, indiceDoTrechoAtual(indiceGuiada = -1, naTela = { null }))
    }

    @Test
    fun naTelaNaoEConsultadaComGuiada() {
        var consultouTela = false
        indiceDoTrechoAtual(indiceGuiada = 0, naTela = {
            consultouTela = true
            0
        })
        assertFalse("Com leitura guiada, o índice da tela não deveria ser consultado", consultouTela)
    }
}
