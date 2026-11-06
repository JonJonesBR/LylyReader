package com.jonjonesbr.audiobookgen.ui

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EspacoSobControlesTest {

    @Test
    fun barraInvisivelCabeNoRespiro() {
        // Painel da guiada cedido à barra da seleção: INVISIBLE, mas continua ocupando o lugar.
        assertTrue(barraContaNoRespiro(View.INVISIBLE, altura = 135, paiNaTela = true))
    }

    @Test
    fun barraGoneNaoCabeNoRespiro() {
        assertFalse(barraContaNoRespiro(View.GONE, altura = 135, paiNaTela = true))
    }

    @Test
    fun barraSemAlturaNaoCabeNoRespiro() {
        assertFalse(barraContaNoRespiro(View.VISIBLE, altura = 0, paiNaTela = true))
    }

    @Test
    fun barraComPaiEscondidoNaoCabeNoRespiro() {
        assertFalse(barraContaNoRespiro(View.VISIBLE, altura = 135, paiNaTela = false))
    }

    @Test
    fun painelCedidoEBarraEmbaixoDAOMesmoRespiroDoPainelSozinho() {
        // Painel no topo 1000, barra embaixo no topo 1055, base da lista em 1135 (sem translação).
        val soPainel = respiroSobAsBarras(1135, listOf(1000), minimo = 96, folga = 16)
        val painelEBarra = respiroSobAsBarras(1135, listOf(1000, 1055), minimo = 96, folga = 16)
        assertEquals(135 + 16, soPainel)
        assertEquals(soPainel, painelEBarra)
    }

    @Test
    fun semBarrasDaOMinimo() {
        assertEquals(96, respiroSobAsBarras(1135, emptyList(), minimo = 96, folga = 16))
    }

    @Test
    fun comMiniPlayerValeOTopoDaBarraMaisAlta() {
        // Mini player no topo 1080 e barra embaixo no topo 1000 (acima do mini player): vale a barra.
        assertEquals(135 + 16, respiroSobAsBarras(1135, listOf(1080, 1000), minimo = 96, folga = 16))
    }

    @Test
    fun modoVerticalMantemAReservaDe96() {
        assertEquals(96, minimoDoRespiro(paginasAtivas = false, minimoVertical = 96, folga = 16))
    }

    @Test
    fun modoPaginasTrocaAReservaPelaFolga() {
        // rb-26b (passo 7): no modo páginas a reserva de 96 comia o texto; sobra só a folga.
        assertEquals(16, minimoDoRespiro(paginasAtivas = true, minimoVertical = 96, folga = 16))
    }

    @Test
    fun modoPaginasSemBarrasFicaNaFolga() {
        assertEquals(16, respiroSobAsBarras(1135, emptyList(), minimo = minimoDoRespiro(true, 96, 16), folga = 16))
    }

    @Test
    fun modoPaginasComPainelCompactoFicaNaBarraMaisFolga() {
        // Painel da paisagem com ~56dp: o topo dele fica 56 acima da base da lista.
        val minimo = minimoDoRespiro(true, 96, 16)
        assertEquals(56 + 16, respiroSobAsBarras(1135, listOf(1135 - 56), minimo = minimo, folga = 16))
    }
}
