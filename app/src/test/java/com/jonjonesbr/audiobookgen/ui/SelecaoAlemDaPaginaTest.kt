package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelecaoAlemDaPaginaTest {

    // Página de 100 a 900 (topo e corte em coordenadas da lista).
    private val topo = 100
    private val corte = 900

    @Test
    fun fimMexidoAbaixoDoCorteViraParaAFrente() {
        val s = SelecaoNaPagina(topoDoInicio = 300, baseDoFim = 960, moveuInicio = false, moveuFim = true)
        assertEquals(1, sentidoParaASelecao(s, topo, corte))
    }

    @Test
    fun inicioMexidoAcimaDoTopoVaiParaTras() {
        val s = SelecaoNaPagina(topoDoInicio = 40, baseDoFim = 500, moveuInicio = true, moveuFim = false)
        assertEquals(-1, sentidoParaASelecao(s, topo, corte))
    }

    @Test
    fun selecaoDentroDaPaginaNaoVira() {
        val s = SelecaoNaPagina(topoDoInicio = 300, baseDoFim = 600, moveuInicio = true, moveuFim = true)
        assertEquals(0, sentidoParaASelecao(s, topo, corte))
    }

    @Test
    fun semNenhumMovimentoNaoVira() {
        val s = SelecaoNaPagina(topoDoInicio = 40, baseDoFim = 960, moveuInicio = false, moveuFim = false)
        assertEquals(0, sentidoParaASelecao(s, topo, corte))
    }

    @Test
    fun inicioEscondidoComOFimMexidoNaoVoltaParaTras() {
        // O início está acima do topo (escondido), mas só o fim foi mexido e ele está dentro da página: nada a virar.
        val s = SelecaoNaPagina(topoDoInicio = 40, baseDoFim = 600, moveuInicio = false, moveuFim = true)
        assertEquals(0, sentidoParaASelecao(s, topo, corte))
    }

    @Test
    fun fimEscondidoComOInicioMexidoNaoVaiParaAFrente() {
        // Só o início foi mexido: o fim fora da página não puxa a virada para a frente.
        val s = SelecaoNaPagina(topoDoInicio = 300, baseDoFim = 960, moveuInicio = true, moveuFim = false)
        assertEquals(0, sentidoParaASelecao(s, topo, corte))
    }
}
