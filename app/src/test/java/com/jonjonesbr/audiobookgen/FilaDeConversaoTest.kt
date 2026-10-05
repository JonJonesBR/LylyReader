package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.service.ConversionQueueCoordinator
import com.jonjonesbr.audiobookgen.service.FilaDeConversao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FilaDeConversaoTest {
    private fun fila(vararg nomes: String) = nomes.mapIndexed { i, n -> QueueItem("${i + 1}", n, "/tmp/$n") }.toMutableList()

    @Test
    fun `concluir um item remove da fila e inicia o proximo pendente`() {
        val itens = fila("a.epub", "b.epub", "c.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!

        val fechamento = FilaDeConversao.fecharItem(itens, "1", inicio.runToken, "/tmp/a.mp3", null, true) { "edge" }

        assertNotNull(fechamento)
        val proximo = fechamento!!.proximo!!
        assertEquals("2", proximo.item.id)
        assertEquals(QueueStatus.PROCESSANDO, proximo.item.status)
        assertEquals("edge", proximo.item.motor)
        assertEquals(listOf("2", "3"), itens.map { it.id })
    }

    @Test
    fun `ultimo item concluido deixa a fila sem proximo`() {
        val itens = fila("a.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!

        val fechamento = FilaDeConversao.fecharItem(itens, "1", inicio.runToken, "/tmp/a.mp3", null, true) { "edge" }

        assertNull(fechamento!!.proximo)
        assertEquals(0, itens.size)
    }

    @Test
    fun `erro marca o item e a fila segue para o proximo`() {
        val itens = fila("a.epub", "b.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!

        val fechamento = FilaDeConversao.fecharItem(itens, "1", inicio.runToken, null, "falhou", true) { "piper" }

        assertEquals(QueueStatus.ERRO, itens[0].status)
        assertEquals("falhou", itens[0].erro)
        assertSame(itens[1], fechamento!!.proximo!!.item)
    }

    @Test
    fun `cancelar nao avanca e deixa os demais pendentes`() {
        val itens = fila("a.epub", "b.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!

        val fechamento = FilaDeConversao.fecharItem(itens, "1", inicio.runToken, null, "cancelada", false) { "piper" }

        assertNull(fechamento!!.proximo)
        assertEquals(QueueStatus.ERRO, itens[0].status)
        assertEquals(QueueStatus.PENDENTE, itens[1].status)
    }

    @Test
    fun `execucao obsoleta com outro runToken e ignorada`() {
        val itens = fila("a.epub", "b.epub")
        ConversionQueueCoordinator(itens).startNext("piper")

        assertNull(FilaDeConversao.fecharItem(itens, "1", "token-antigo", "/tmp/a.mp3", null, true) { "piper" })
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertNull(FilaDeConversao.fecharItem(itens, "inexistente", null, null, "x", true) { "piper" })
    }

    @Test
    fun `item restaurado sem runToken aceita o fechamento`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.ERRO // restaurado após o processo morrer: o token não é persistido

        val fechamento = FilaDeConversao.fecharItem(itens, "1", "qualquer", "/tmp/a.mp3", null, true) { "piper" }

        assertNotNull(fechamento)
        assertEquals(0, itens.size)
    }

    @Test
    fun `sincronizar reconhece o item em processamento`() {
        val itens = fila("a.epub", "b.epub")
        val coordenador = ConversionQueueCoordinator(itens)
        coordenador.sincronizar()
        assertNull(coordenador.currentItem)

        itens[1].status = QueueStatus.PROCESSANDO
        coordenador.sincronizar()
        assertSame(itens[1], coordenador.currentItem)
    }
}
