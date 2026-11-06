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

    @Test
    fun reservaDaBarraVaiDaBaseAteOTopoDelaMaisAFolga() {
        // Área acaba na base da lista (1135) e a barra de 120 px fica encostada nela: a reserva é a barra mais a folga.
        assertEquals(
            136,
            reservaAteABarra(
                baseDaLista = 1135,
                fundoDaArea = 1135,
                margemDeBaixo = 0,
                alturaDaBarra = 120,
                folga = 16,
            ),
        )
    }

    @Test
    fun reservaDaBarraSobeComAMargemDoMiniPlayer() {
        // Mini player de 60 px por baixo da barra: a barra fica 60 px acima da área, e a reserva cresce junto.
        assertEquals(
            196,
            reservaAteABarra(
                baseDaLista = 1135,
                fundoDaArea = 1135,
                margemDeBaixo = 60,
                alturaDaBarra = 120,
                folga = 16,
            ),
        )
    }

    @Test
    fun barraMaisAltaQueATiraMandaNaReserva() {
        // Números do Robolectric (rb-67, item 18b): tira com topo 89 px acima da base, barra de 99 px. O respiro da
        // tira dá 105 (89 + folga); a reserva da barra dá 115 (99 + folga) e manda.
        val tira = respiroSobAsBarras(1135, listOf(1135 - 89), minimo = 16, folga = 16)
        val barra = reservaAteABarra(1135, 1135, 0, alturaDaBarra = 99, folga = 16)
        assertEquals(115, maxOf(tira, barra))
    }

    @Test
    fun barraNaoMaisAltaQueATiraCustaZero() {
        // Barra de 80 px, abaixo da tira de 89: a reserva dá 96 e não passa do respiro da tira (105).
        val tira = respiroSobAsBarras(1135, listOf(1135 - 89), minimo = 16, folga = 16)
        val barra = reservaAteABarra(1135, 1135, 0, alturaDaBarra = 80, folga = 16)
        assertEquals(105, maxOf(tira, barra))
    }
}
