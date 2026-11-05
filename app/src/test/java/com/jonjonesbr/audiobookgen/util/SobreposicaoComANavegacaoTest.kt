package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SobreposicaoComANavegacaoTest {

    private val altura = 2000
    private val nav = 120

    @Test fun paiJaDescontouANavegacaoDaZeroDeSobreposicao() {
        // A view termina exatamente acima da barra: o pai já a pôs ali.
        assertEquals(
            0,
            sobreposicaoComANavegacao(fundoNaJanela = altura - nav, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun semDescontarDaAAlturaDaNavegacao() {
        // A view termina no fim da janela: passa pela barra inteira.
        assertEquals(
            nav,
            sobreposicaoComANavegacao(fundoNaJanela = altura, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun meioAMeioDaAParteQueEntraNaBarra() {
        assertEquals(
            nav / 2,
            sobreposicaoComANavegacao(fundoNaJanela = altura - nav / 2, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun viewAcimaDaBarraNuncaFicaNegativa() {
        assertEquals(
            0,
            sobreposicaoComANavegacao(fundoNaJanela = altura - nav - 50, alturaDaJanela = altura, navInferior = nav)
        )
    }

    @Test fun semBarraDeNavegacaoNaoSobrepoe() {
        assertEquals(
            0,
            sobreposicaoComANavegacao(fundoNaJanela = altura, alturaDaJanela = altura, navInferior = 0)
        )
    }
}
