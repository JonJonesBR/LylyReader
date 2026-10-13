package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.service.ConversionQueueCoordinator
import com.jonjonesbr.audiobookgen.service.FilaDeConversao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `marcar em andamento retoma item interrompido e grava o runToken novo`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.ERRO
        itens[0].erro = ERRO_FILA_INTERROMPIDA

        val marcado = FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo")

        assertTrue(marcado)
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertNull(itens[0].erro)
        assertEquals("token-novo", itens[0].runToken)
    }

    @Test
    fun `marcar em andamento aceita item em processamento sem token e grava o token do worker`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO

        assertTrue(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertEquals("token-novo", itens[0].runToken)
    }

    @Test
    fun `marcar em andamento com token nulo no worker mantem o token do item`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.ERRO
        itens[0].erro = ERRO_FILA_INTERROMPIDA
        itens[0].runToken = "token-antigo"

        assertTrue(FilaDeConversao.marcarEmAndamento(itens, "1", null))
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertEquals("token-antigo", itens[0].runToken)
    }

    @Test
    fun `marcar em andamento com o mesmo token ja gravado devolve falso`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[0].runToken = "token-novo"

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals("token-novo", itens[0].runToken)
    }

    @Test
    fun `marcar em andamento com token diferente do item nao sobrescreve e devolve falso`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[0].runToken = "token-outro"

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertEquals("token-outro", itens[0].runToken)
    }

    @Test
    fun `marcar em andamento nao mexe em erro de outro motivo`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.ERRO
        itens[0].erro = "falhou"

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals(QueueStatus.ERRO, itens[0].status)
        assertEquals("falhou", itens[0].erro)
        assertNull(itens[0].runToken)
    }

    @Test
    fun `marcar em andamento nao mexe em item concluido`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.CONCLUIDO

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals(QueueStatus.CONCLUIDO, itens[0].status)
        assertNull(itens[0].runToken)
    }

    @Test
    fun `marcar em andamento nao mexe em item pendente`() {
        val itens = fila("a.epub")

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))
        assertEquals(QueueStatus.PENDENTE, itens[0].status)
    }

    @Test
    fun `marcar em andamento devolve falso para item inexistente`() {
        val itens = fila("a.epub")

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "99", "token-novo"))
        assertEquals(QueueStatus.PENDENTE, itens[0].status)
    }

    @Test
    fun `marcar em andamento retoma item pausado e grava o runToken`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PAUSADO
        itens[0].progressoPct = 30

        assertTrue(FilaDeConversao.marcarEmAndamento(itens, "1", "token-novo"))

        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertNull(itens[0].erro)
        assertEquals("token-novo", itens[0].runToken)
        assertEquals(30, itens[0].progressoPct)
    }

    @Test
    fun `marcar em andamento com item pausado de outro runToken nao sobrescreve`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PAUSADO
        itens[0].runToken = "token-a"

        assertFalse(FilaDeConversao.marcarEmAndamento(itens, "1", "token-b"))
        assertEquals(QueueStatus.PAUSADO, itens[0].status)
        assertEquals("token-a", itens[0].runToken)
    }

    @Test
    fun `remover pausado tira da fila so o item pausado com esse id`() {
        val itens = fila("a.epub", "b.epub")
        itens[0].status = QueueStatus.PAUSADO

        assertTrue(FilaDeConversao.removerPausado(itens, "1"))

        assertEquals(1, itens.size)
        assertEquals("2", itens[0].id)
    }

    @Test
    fun `remover pausado nao mexe em item de outro status nem em outro id`() {
        val itens = fila("a.epub", "b.epub", "c.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[1].status = QueueStatus.PAUSADO

        assertFalse(FilaDeConversao.removerPausado(itens, "1"))
        assertFalse(FilaDeConversao.removerPausado(itens, "3"))
        assertFalse(FilaDeConversao.removerPausado(itens, "99"))

        assertEquals(3, itens.size)
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertEquals(QueueStatus.PAUSADO, itens[1].status)
    }

    @Test
    fun `pausar item em processamento marca pausado sem avancar a fila e mantem o progresso`() {
        val itens = fila("a.epub", "b.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!
        itens[0].progressoPct = 45

        assertTrue(FilaDeConversao.pausarItem(itens, "1", inicio.runToken))

        assertEquals(QueueStatus.PAUSADO, itens[0].status)
        assertEquals(45, itens[0].progressoPct)
        assertEquals(QueueStatus.PENDENTE, itens[1].status)
    }

    @Test
    fun `pausar limpa o erro do item`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[0].erro = "algo"

        assertTrue(FilaDeConversao.pausarItem(itens, "1", null))

        assertEquals(QueueStatus.PAUSADO, itens[0].status)
        assertNull(itens[0].erro)
    }

    @Test
    fun `pausar com runToken diferente do item nao muda nada`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[0].runToken = "token-a"

        assertFalse(FilaDeConversao.pausarItem(itens, "1", "token-b"))
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
        assertEquals("token-a", itens[0].runToken)
    }

    @Test
    fun `pausar com runToken nulo no worker pausa o item que tem token`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO
        itens[0].runToken = "token-a"

        assertTrue(FilaDeConversao.pausarItem(itens, "1", null))
        assertEquals(QueueStatus.PAUSADO, itens[0].status)
    }

    @Test
    fun `pausar nao mexe em item que nao esta processando`() {
        val estados = listOf(QueueStatus.PENDENTE, QueueStatus.CONCLUIDO, QueueStatus.ERRO, QueueStatus.PAUSADO)
        for (estado in estados) {
            val itens = fila("a.epub")
            itens[0].status = estado
            itens[0].erro = "mantido"

            assertFalse(FilaDeConversao.pausarItem(itens, "1", null))
            assertEquals(estado, itens[0].status)
            assertEquals("mantido", itens[0].erro)
        }
    }

    @Test
    fun `pausar devolve falso para item inexistente`() {
        val itens = fila("a.epub")
        itens[0].status = QueueStatus.PROCESSANDO

        assertFalse(FilaDeConversao.pausarItem(itens, "99", null))
        assertEquals(QueueStatus.PROCESSANDO, itens[0].status)
    }

    @Test
    fun `pausar, retomar e concluir o mesmo item chega a CONCLUIDO e sai da fila`() {
        val itens = fila("a.epub")
        val inicio = ConversionQueueCoordinator(itens).startNext("piper")!!
        val item = itens[0]

        assertTrue(FilaDeConversao.pausarItem(itens, "1", inicio.runToken))
        assertEquals(QueueStatus.PAUSADO, item.status)
        assertTrue(FilaDeConversao.marcarEmAndamento(itens, "1", inicio.runToken))
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        FilaDeConversao.fecharItem(itens, "1", inicio.runToken, "/tmp/a.mp3", null, false) { "edge" }

        assertEquals(QueueStatus.CONCLUIDO, item.status)
        assertTrue(itens.isEmpty())
    }
}
