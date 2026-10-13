package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinoDaParadaTest {

    @Test
    fun `pausar pelo app vai para pausada, com ou sem cancelamento do sistema`() {
        assertEquals(DestinoDaParada.PAUSADA, RegraDaParada.destino(MotivoParada.PAUSAR, canceladoPeloApp = true))
        assertEquals(DestinoDaParada.PAUSADA, RegraDaParada.destino(MotivoParada.PAUSAR, canceladoPeloApp = false))
    }

    @Test
    fun `limite do sistema com pedido de pausa vai para pausada`() {
        assertEquals(DestinoDaParada.PAUSADA, RegraDaParada.destino(MotivoParada.LIMITE_DO_SISTEMA, canceladoPeloApp = true))
        assertEquals(DestinoDaParada.PAUSADA, RegraDaParada.destino(MotivoParada.LIMITE_DO_SISTEMA, canceladoPeloApp = false))
    }

    @Test
    fun `cancelar pelo app vai para cancelada, com ou sem cancelamento do sistema`() {
        assertEquals(DestinoDaParada.CANCELADA, RegraDaParada.destino(MotivoParada.CANCELAR, canceladoPeloApp = true))
        assertEquals(DestinoDaParada.CANCELADA, RegraDaParada.destino(MotivoParada.CANCELAR, canceladoPeloApp = false))
    }

    @Test
    fun `sem pedido e cancelada pelo app vai para cancelada`() {
        assertEquals(DestinoDaParada.CANCELADA, RegraDaParada.destino(null, canceladoPeloApp = true))
    }

    @Test
    fun `sem pedido e sem cancelamento do app e parada do sistema vai para interrompida`() {
        assertEquals(DestinoDaParada.INTERROMPIDA, RegraDaParada.destino(null, canceladoPeloApp = false))
    }
}
