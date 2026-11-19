package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FolgaParaBarraDeNavegacaoTest {

    private val altura = 2000
    private val nav = 120

    @Test fun folhaAncoradaNoFundoDaJanelaDaAlturaDaBarra() {
        // A folha termina no fim da janela: a folga é a barra inteira.
        assertEquals(
            nav,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun folhaJaAcimaDaBarraDaZeroDeFolga() {
        // O Material já levantou a folha: ela termina exatamente acima da barra.
        assertEquals(
            0,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura - nav, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun meioAMeioDaBarraDaFolgaParcial() {
        assertEquals(
            nav / 2,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura - nav / 2, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun folhaRecolhidaComFundoForaDaTelaFicaLimitadaABarra() {
        // Recolhida, o fundo passa da borda da janela; a folga não passa da altura da barra.
        assertEquals(
            nav,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura + 300, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun barraDeZeroPixelsDaZeroDeFolga() {
        // Navegação por gestos: não há barra para subir o conteúdo.
        assertEquals(
            0,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura, alturaDaJanela = altura, navInferior = 0)
        )
    }

    @Test fun navegacaoNegativaViraZeroDeFolga() {
        assertEquals(
            0,
            folgaParaBarraDeNavegacao(fundoNaJanela = altura, alturaDaJanela = altura, navInferior = -50)
        )
    }
}
