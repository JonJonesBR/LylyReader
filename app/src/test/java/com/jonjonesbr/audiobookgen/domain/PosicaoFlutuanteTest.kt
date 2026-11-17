package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosicaoFlutuanteTest {

    @Test
    fun `limites respeitam area segura e margem`() {
        val limites = PosicaoFlutuante.calcularLimites(
            larguraTela = 1080f,
            alturaTela = 2400f,
            tamanhoBotao = 168f,
            margem = 36f,
            areaSegura = AreaSegura(esquerda = 10f, topo = 80f, direita = 20f, fundo = 120f),
        )
        assertEquals(46f, limites.minX, 0.01f)
        assertEquals(856f, limites.maxX, 0.01f)
        assertEquals(116f, limites.minY, 0.01f)
        assertEquals(2076f, limites.maxY, 0.01f)
    }

    @Test
    fun `limitar prende coordenada fora dos limites`() {
        val limites = LimitesFlutuante(minX = 20f, maxX = 800f, minY = 50f, maxY = 1800f)
        assertEquals(20f to 50f, PosicaoFlutuante.limitar(-30f, 0f, limites))
        assertEquals(800f to 1800f, PosicaoFlutuante.limitar(999f, 2500f, limites))
        assertEquals(300f to 600f, PosicaoFlutuante.limitar(300f, 600f, limites))
    }

    @Test
    fun `borda magnetica puxa para esquerda quando solto na metade esquerda`() {
        val borda = PosicaoFlutuante.bordaMagnetica(x = 200f, larguraBotao = 160f, larguraTela = 1000f)
        assertEquals(BordaLateral.ESQUERDA, borda)
    }

    @Test
    fun `borda magnetica puxa para direita quando solto na metade direita ou no meio`() {
        val direita = PosicaoFlutuante.bordaMagnetica(x = 600f, larguraBotao = 160f, larguraTela = 1000f)
        val exatoMeio = PosicaoFlutuante.bordaMagnetica(x = 420f, larguraBotao = 160f, larguraTela = 1000f)
        assertEquals(BordaLateral.DIREITA, direita)
        assertEquals(BordaLateral.DIREITA, exatoMeio)
    }

    @Test
    fun `xAncorado devolve borda correspondente`() {
        val limites = LimitesFlutuante(minX = 24f, maxX = 840f, minY = 60f, maxY = 1900f)
        assertEquals(24f, PosicaoFlutuante.xAncorado(BordaLateral.ESQUERDA, limites), 0.01f)
        assertEquals(840f, PosicaoFlutuante.xAncorado(BordaLateral.DIREITA, limites), 0.01f)
    }

    @Test
    fun `fracao vertical e yDeFracao preservam altura relativa`() {
        val limites = LimitesFlutuante(minX = 24f, maxX = 840f, minY = 100f, maxY = 1100f)
        val fracao = PosicaoFlutuante.fracaoVertical(350f, limites)
        assertEquals(0.25f, fracao, 0.001f)
        assertEquals(350f, PosicaoFlutuante.yDeFracao(fracao, limites), 0.01f)
    }

    @Test
    fun `ehArraste distingue toque curto de movimento acima do touchSlop`() {
        assertFalse(PosicaoFlutuante.ehArraste(dxTotal = 4f, dyTotal = 5f, touchSlop = 12f))
        assertTrue(PosicaoFlutuante.ehArraste(dxTotal = 10f, dyTotal = 10f, touchSlop = 12f))
    }
}
