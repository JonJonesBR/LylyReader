package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DescidaNoFimDaListaTest {

    @Test
    fun rolagemVerticalLevaADescidaInteiraParaOFim() {
        // Barra da seleção com 120 px de descida: o espaço depois do último parágrafo é esses 120 px.
        assertEquals(120, descidaNoFimPx(descidaPx = 120, rolagemVertical = true))
    }

    @Test
    fun descidaNegativaContaComoZero() {
        assertEquals(0, descidaNoFimPx(descidaPx = -30, rolagemVertical = true))
    }

    @Test
    fun rolagemHorizontalNaoLevaDescida() {
        // Na página horizontal a página tem a altura da área: mudar o espaço do fim ali redimensionaria a página.
        assertEquals(0, descidaNoFimPx(descidaPx = 120, rolagemVertical = false))
    }

    @Test
    fun voltaSaiJuntaSoAteOQueAListaRolou() {
        // A descida é 120, mas a lista rolou só 50 desde o topo: o empurrão só desce 50, então só 50 sai junto.
        assertEquals(50, quantoSaiJuntoNaVolta(descida = 120, restanteAteOFim = 0, rolagemDoTopo = 50))
    }

    @Test
    fun capituloCurtoSemRolagemDoTopoNaoLevaNada() {
        // Conteúdo menor que a área: a descida fez a lista rolar, mas ela não rolou desde o topo.
        assertEquals(0, quantoSaiJuntoNaVolta(descida = 120, restanteAteOFim = 0, rolagemDoTopo = 0))
    }

    @Test
    fun saiOQueCabeNoRestanteAteOFim() {
        // O fim ainda tem 30 px de folga: só 90 da descida precisam de empurrão.
        assertEquals(90, quantoSaiJuntoNaVolta(descida = 120, restanteAteOFim = 30, rolagemDoTopo = 500))
    }

    @Test
    fun semUltimoParagrafoNaTelaNaoLevaNada() {
        assertEquals(0, quantoSaiJuntoNaVolta(descida = 120, restanteAteOFim = null, rolagemDoTopo = 500))
    }
}
