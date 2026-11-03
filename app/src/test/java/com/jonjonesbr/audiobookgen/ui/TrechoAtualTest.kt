package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrechoAtualTest {

    @Test
    fun guiadaVenceATela() {
        assertEquals(2, indiceDoTrechoAtual(indiceGuiada = 2, rolouParaLonge = false, naTela = { 0 }))
    }

    @Test
    fun semGuiadaUsaATela() {
        assertEquals(1, indiceDoTrechoAtual(indiceGuiada = -1, rolouParaLonge = false, naTela = { 1 }))
        assertEquals(1, indiceDoTrechoAtual(indiceGuiada = null, rolouParaLonge = false, naTela = { 1 }))
    }

    @Test
    fun semNenhumDosDoisDaMenosUm() {
        assertEquals(-1, indiceDoTrechoAtual(indiceGuiada = null, rolouParaLonge = false, naTela = { null }))
        assertEquals(-1, indiceDoTrechoAtual(indiceGuiada = -1, rolouParaLonge = false, naTela = { null }))
    }

    @Test
    fun naTelaNaoEConsultadaComGuiada() {
        var consultouTela = false
        indiceDoTrechoAtual(indiceGuiada = 0, rolouParaLonge = false, naTela = {
            consultouTela = true
            0
        })
        assertFalse("Com leitura guiada, o índice da tela não deveria ser consultado", consultouTela)
    }

    @Test
    fun rolouParaLongeUsaATela() {
        assertEquals(5, indiceDoTrechoAtual(indiceGuiada = 2, rolouParaLonge = true, naTela = { 5 }))
        assertEquals(5, indiceDoTrechoAtual(indiceGuiada = -1, rolouParaLonge = true, naTela = { 5 }))
    }

    @Test
    fun semRolarUsaAGuiada() {
        assertEquals(2, indiceDoTrechoAtual(indiceGuiada = 2, rolouParaLonge = false, naTela = { 5 }))
    }

    @Test
    fun rolouSemTelaCaiNaGuiada() {
        assertEquals(2, indiceDoTrechoAtual(indiceGuiada = 2, rolouParaLonge = true, naTela = { null }))
    }

    @Test
    fun semGuiadaUsaATelaMesmoRolado() {
        assertEquals(4, indiceDoTrechoAtual(indiceGuiada = null, rolouParaLonge = true, naTela = { 4 }))
        assertEquals(4, indiceDoTrechoAtual(indiceGuiada = -1, rolouParaLonge = true, naTela = { 4 }))
    }

    @Test
    fun rolouSemNadaDaMenosUm() {
        assertEquals(-1, indiceDoTrechoAtual(indiceGuiada = null, rolouParaLonge = true, naTela = { null }))
    }
}
