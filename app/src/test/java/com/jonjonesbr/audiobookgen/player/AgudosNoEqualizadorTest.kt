package com.jonjonesbr.audiobookgen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** rb-2: regra por banda do corte de agudos, sem Android (a faixa do equalizador entra como parâmetro). */
class AgudosNoEqualizadorTest {

    private fun nivel(centroHz: Int, agudosDb: Int, minMb: Int = -1500, maxMb: Int = 1500): Int? =
        AgudosNoEqualizador.nivelDaBandaMb(centroHz, agudosDb, minMb, maxMb)

    @Test
    fun bandaDeAgudosCortaOValorInteiro() {
        assertEquals(-600, nivel(8000, 6))
        assertEquals(-800, nivel(16000, 8))
    }

    @Test
    fun bandaMedioAltaCortaMetade() {
        assertEquals(-300, nivel(2500, 6))
        assertEquals(-300, nivel(7999, 6))
        assertEquals(-150, nivel(2500, 3))
    }

    @Test
    fun bandasAbaixoDe2500NaoCortam() {
        assertNull(nivel(2499, 6))
        assertNull(nivel(60, 8))
    }

    @Test
    fun semCorteQuandoAgudosNaoEhPositivo() {
        assertNull(nivel(8000, 0))
        assertNull(nivel(8000, -2))
    }

    @Test
    fun nivelFicaLimitadoPeloMinimoDaFaixa() {
        assertEquals(-500, nivel(8000, 8, minMb = -500, maxMb = 500))
    }

    @Test
    fun nivelFicaLimitadoPeloMaximoDaFaixa() {
        assertEquals(-200, nivel(2500, 2, minMb = -1500, maxMb = -200))
    }
}
