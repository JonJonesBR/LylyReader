package com.jonjonesbr.audiobookgen.domain

import android.os.Build
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `antes do android 12 a parada conta como cancelada pelo app, qualquer que seja o motivo`() {
        assertTrue(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.R, WorkInfo.STOP_REASON_PREEMPT))
        assertTrue(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.R, WorkInfo.STOP_REASON_NOT_STOPPED))
        assertTrue(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.O, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW))
    }

    @Test
    fun `a partir do android 12 cancelamento pelo app e motivo nao gravado contam como cancelados`() {
        assertTrue(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.S, WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertTrue(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.S, WorkInfo.STOP_REASON_NOT_STOPPED))
    }

    @Test
    fun `a partir do android 12 parada pelo sistema nao conta como cancelada pelo app`() {
        assertFalse(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.S, WorkInfo.STOP_REASON_PREEMPT))
        assertFalse(RegraDaParada.canceladoPeloApp(Build.VERSION_CODES.VANILLA_ICE_CREAM, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW))
    }
}
