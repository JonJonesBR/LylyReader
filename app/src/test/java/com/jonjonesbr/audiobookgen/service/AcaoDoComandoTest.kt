package com.jonjonesbr.audiobookgen.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcaoDoComandoTest {

    @Test
    fun `acao de inicio vira comando iniciar`() {
        assertEquals(ComandoLeituraGuiada.INICIAR, acaoDoComando(GuidedReadingService.ACTION_START))
    }

    @Test
    fun `acao de play pause vira alternar reproducao`() {
        assertEquals(ComandoLeituraGuiada.ALTERNAR_REPRODUCAO, acaoDoComando(GuidedReadingService.ACTION_PLAY_PAUSE))
    }

    @Test
    fun `acoes de anterior e proximo viram os comandos correspondentes`() {
        assertEquals(ComandoLeituraGuiada.ANTERIOR, acaoDoComando(GuidedReadingService.ACTION_PREV))
        assertEquals(ComandoLeituraGuiada.PROXIMO, acaoDoComando(GuidedReadingService.ACTION_NEXT))
    }

    @Test
    fun `acao de soneca vira comando soneca`() {
        assertEquals(ComandoLeituraGuiada.SONECA, acaoDoComando(GuidedReadingService.ACTION_SLEEP))
    }

    @Test
    fun `acao de parar vira comando parar`() {
        assertEquals(ComandoLeituraGuiada.PARAR, acaoDoComando(GuidedReadingService.ACTION_STOP))
    }

    @Test
    fun `acao ausente nao tem comando`() {
        assertNull(acaoDoComando(null))
    }

    @Test
    fun `acao desconhecida nao tem comando`() {
        assertNull(acaoDoComando("com.jonjonesbr.audiobookgen.ACAO_QUE_NAO_EXISTE"))
    }

    @Test
    fun `acao vazia nao tem comando`() {
        assertNull(acaoDoComando(""))
    }
}
