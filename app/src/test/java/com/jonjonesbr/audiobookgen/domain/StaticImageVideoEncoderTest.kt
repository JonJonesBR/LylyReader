package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticImageVideoEncoderTest {

    @Test
    fun `quadros antes do total viram frame com pts de cinco segundos por quadro`() {
        assertEquals(AcaoQuadro.Frame(0L), acaoDoQuadro(0, 2))
        assertEquals(AcaoQuadro.Frame(INTERVALO_QUADRO_VIDEO_US), acaoDoQuadro(1, 2))
    }

    @Test
    fun `indice igual ao total sinaliza o fim do fluxo`() {
        assertEquals(AcaoQuadro.Fim, acaoDoQuadro(2, 2))
    }

    @Test
    fun `indice acima do total continua sendo fim do fluxo`() {
        assertEquals(AcaoQuadro.Fim, acaoDoQuadro(7, 2))
    }

    @Test
    fun `total zero ja comeca pelo fim do fluxo`() {
        assertEquals(AcaoQuadro.Fim, acaoDoQuadro(0, 0))
    }

    @Test
    fun `pts dos quadros cresce estritamente e so termina no fim do fluxo`() {
        val total = 5
        val acoes = (0 until 10).map { acaoDoQuadro(it, total) }
        val pts = acoes.take(total).map { (it as AcaoQuadro.Frame).ptsUs }
        for (i in 1 until pts.size) {
            assertTrue("pts do quadro $i deve ser maior que o anterior", pts[i] > pts[i - 1])
        }
        assertEquals(List(total) { it * INTERVALO_QUADRO_VIDEO_US }, pts)
        assertEquals(List(10 - total) { AcaoQuadro.Fim }, acoes.drop(total))
    }
}
