package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.domain.EstadoDownload.BAIXANDO
import com.jonjonesbr.audiobookgen.domain.EstadoDownload.NAO_BAIXADO
import com.jonjonesbr.audiobookgen.domain.EstadoDownload.NA_FILA
import com.jonjonesbr.audiobookgen.domain.EstadoDownload.PAUSADO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilaDeDownloadsTest {

    @Test
    fun `comeca so ate o limite de simultaneos mantendo a ordem da fila`() {
        val estados = mapOf("a" to NA_FILA, "b" to NA_FILA, "c" to NA_FILA)
        assertEquals(listOf("a", "b"), proximosDaFila(listOf("a", "b", "c"), estados, 2))
    }

    @Test
    fun `quem ja esta baixando ocupa vaga`() {
        val estados = mapOf("x" to BAIXANDO, "a" to NA_FILA, "b" to NA_FILA)
        assertEquals(listOf("a"), proximosDaFila(listOf("a", "b"), estados, 2))
    }

    @Test
    fun `sem vaga livre nada comeca`() {
        val estados = mapOf("x" to BAIXANDO, "y" to BAIXANDO, "a" to NA_FILA)
        assertTrue(proximosDaFila(listOf("a"), estados, 2).isEmpty())
    }

    @Test
    fun `item pausado ou fora da fila nao comeca sozinho`() {
        val estados = mapOf("a" to PAUSADO, "b" to NAO_BAIXADO, "c" to NA_FILA)
        assertEquals(listOf("c"), proximosDaFila(listOf("a", "b", "c"), estados, 3))
    }

    @Test
    fun `limite fora da faixa e ajustado`() {
        val estados = (1..6).associate { "i$it" to NA_FILA }
        val fila = estados.keys.toList()
        assertEquals(1, proximosDaFila(fila, estados, 0).size)
        assertEquals(DOWNLOADS_SIMULTANEOS_MAX, proximosDaFila(fila, estados, 9).size)
    }

    @Test
    fun `fracao agregada soma bytes e totais`() {
        assertEquals(0.5f, fracaoAgregada(listOf(100L to 200L, 300L to 600L))!!, 0.0001f)
    }

    @Test
    fun `fracao agregada ignora quem ainda nao tem total e e nula sem nenhum total`() {
        assertEquals(0.25f, fracaoAgregada(listOf(50L to 200L, 0L to 0L))!!, 0.0001f)
        assertNull(fracaoAgregada(listOf(0L to 0L)))
        assertNull(fracaoAgregada(emptyList()))
    }

    @Test
    fun `fracao agregada nao passa de um`() {
        assertEquals(1f, fracaoAgregada(listOf(500L to 200L))!!, 0.0001f)
    }

    @Test
    fun `espaco precisa do dobro do tamanho baixado`() {
        assertTrue(espacoSuficiente(livreBytes = 600, aBaixarBytes = 300))
        assertFalse(espacoSuficiente(livreBytes = 599, aBaixarBytes = 300))
    }
}
