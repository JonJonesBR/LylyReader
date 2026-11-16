package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class LeituraLimitadaTest {

    @Test
    fun leTextoDentroDoLimiteEFechaOFluxo() {
        var fechado = false
        val dados = "olá mundo".toByteArray(Charsets.UTF_8)
        val fluxo = object : ByteArrayInputStream(dados) {
            override fun close() {
                fechado = true
                super.close()
            }
        }

        val lido = fluxo.lerTextoComLimite(limiteBytes = 64L)

        assertEquals("olá mundo", lido)
        assertTrue(fechado)
    }

    @Test
    fun leExatamenteNoLimiteSemFalhar() {
        val dados = ByteArray(16) { it.toByte() }
        val lido = ByteArrayInputStream(dados).lerBytesComLimite(limiteBytes = 16L)
        assertArrayEquals(dados, lido)
    }

    @Test
    fun lancaIoExceptionAoUltrapassarOLimiteEFechaOFluxo() {
        var fechado = false
        val dados = ByteArray(20 * 1024) { 'a'.code.toByte() }
        val fluxo = object : ByteArrayInputStream(dados) {
            override fun close() {
                fechado = true
                super.close()
            }
        }

        assertThrows(IOException::class.java) {
            fluxo.lerTextoComLimite(limiteBytes = 1024L)
        }
        assertTrue(fechado)
    }

    @Test
    fun aceitaFluxoVazio() {
        val lido = ByteArrayInputStream(ByteArray(0)).lerTextoComLimite(limiteBytes = 0L)
        assertEquals("", lido)
    }

    @Test
    fun rejeitaLimiteNegativo() {
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(ByteArray(0)).lerBytesComLimite(limiteBytes = -1L)
        }
    }
}
