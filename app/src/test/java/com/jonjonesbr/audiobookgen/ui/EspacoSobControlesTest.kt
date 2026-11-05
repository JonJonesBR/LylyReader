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
}
