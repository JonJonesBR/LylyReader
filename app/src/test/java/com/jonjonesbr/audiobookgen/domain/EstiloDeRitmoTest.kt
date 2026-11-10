package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstiloDeRitmoTest {

    @Test
    fun `fatores das pausas por estilo`() {
        assertEquals(1.9f, EstiloDeRitmo.CALMA.fator, 1e-6f)
        assertEquals(1.45f, EstiloDeRitmo.NATURAL.fator, 1e-6f)
        assertEquals(1.0f, EstiloDeRitmo.FLUIDA.fator, 1e-6f)
    }

    @Test
    fun `entonacao continua so na Fluida`() {
        assertFalse(EstiloDeRitmo.CALMA.entonacaoContinua)
        assertFalse(EstiloDeRitmo.NATURAL.entonacaoContinua)
        assertTrue(EstiloDeRitmo.FLUIDA.entonacaoContinua)
    }

    @Test
    fun `padrao e Fluida`() {
        assertEquals(EstiloDeRitmo.FLUIDA, EstiloDeRitmo.PADRAO)
    }

    @Test
    fun `deId lê os ids gravados`() {
        assertEquals(EstiloDeRitmo.CALMA, EstiloDeRitmo.deId("calma"))
        assertEquals(EstiloDeRitmo.NATURAL, EstiloDeRitmo.deId("natural"))
        assertEquals(EstiloDeRitmo.FLUIDA, EstiloDeRitmo.deId("fluida"))
    }

    @Test
    fun `deId com nulo, vazio ou lixo cai em Fluida`() {
        assertEquals(EstiloDeRitmo.FLUIDA, EstiloDeRitmo.deId(null))
        assertEquals(EstiloDeRitmo.FLUIDA, EstiloDeRitmo.deId(""))
        assertEquals(EstiloDeRitmo.FLUIDA, EstiloDeRitmo.deId("rapidissima"))
    }

    private fun migrar(entonacao: Boolean, pausasDesligadas: Boolean) =
        EstiloDeRitmo.migrar(entonacaoContinuaLigada = entonacao, pausasNaturaisDesligadaExplicita = pausasDesligadas)

    @Test
    fun `migrar a partir das preferencias antigas`() {
        assertEquals(EstiloDeRitmo.FLUIDA, migrar(entonacao = true, pausasDesligadas = false))
        assertEquals(EstiloDeRitmo.FLUIDA, migrar(entonacao = false, pausasDesligadas = true))
        assertEquals(EstiloDeRitmo.FLUIDA, migrar(entonacao = false, pausasDesligadas = false))
        assertEquals(EstiloDeRitmo.FLUIDA, migrar(entonacao = true, pausasDesligadas = true))
    }
}
