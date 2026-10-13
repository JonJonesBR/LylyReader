package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticaPrimeiroPlanoTest {

    @Test
    fun `tipo de servico nas fronteiras de SDK`() {
        assertEquals(TipoServicoConversao.NENHUM, PoliticaPrimeiroPlano.tipoDeServico(28))
        assertEquals(TipoServicoConversao.DATA_SYNC, PoliticaPrimeiroPlano.tipoDeServico(29))
        assertEquals(TipoServicoConversao.DATA_SYNC, PoliticaPrimeiroPlano.tipoDeServico(34))
        assertEquals(TipoServicoConversao.MEDIA_PROCESSING, PoliticaPrimeiroPlano.tipoDeServico(35))
        assertEquals(TipoServicoConversao.MEDIA_PROCESSING, PoliticaPrimeiroPlano.tipoDeServico(36))
    }

    @Test
    fun `tipo de servico abaixo de 29 nao usa primeiro plano tipado`() {
        assertEquals(TipoServicoConversao.NENHUM, PoliticaPrimeiroPlano.tipoDeServico(26))
    }

    @Test
    fun `antes do Android 12 pode iniciar mesmo em segundo plano e sem liberacao de bateria`() {
        assertTrue(PoliticaPrimeiroPlano.podeIniciar(30, appVisivel = false, semOtimizacaoBateria = false))
        assertTrue(PoliticaPrimeiroPlano.podeIniciar(26, appVisivel = false, semOtimizacaoBateria = false))
    }

    @Test
    fun `a partir do Android 12 com app em segundo plano so pode iniciar fora da otimizacao de bateria`() {
        assertFalse(PoliticaPrimeiroPlano.podeIniciar(31, appVisivel = false, semOtimizacaoBateria = false))
        assertTrue(PoliticaPrimeiroPlano.podeIniciar(31, appVisivel = false, semOtimizacaoBateria = true))
    }

    @Test
    fun `a partir do Android 12 com app visivel pode iniciar com ou sem liberacao de bateria`() {
        assertTrue(PoliticaPrimeiroPlano.podeIniciar(31, appVisivel = true, semOtimizacaoBateria = false))
        assertTrue(PoliticaPrimeiroPlano.podeIniciar(36, appVisivel = true, semOtimizacaoBateria = true))
    }

    @Test
    fun `no Android 16 sem visibilidade e com otimizacao de bateria ativa nao inicia`() {
        assertFalse(PoliticaPrimeiroPlano.podeIniciar(36, appVisivel = false, semOtimizacaoBateria = false))
    }
}
