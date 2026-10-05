package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.service.QueueManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class QueueManagerTest {
    @Before
    fun setUp() {
        QueueManager.items.clear()
    }

    @After
    fun tearDown() {
        QueueManager.items.clear()
    }

    @Test
    fun adicionarCreatesPendingItem() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub", "edge")

        assertEquals("book.epub", item.nome)
        assertEquals("/tmp/book.epub", item.caminhoLocal)
        assertEquals("edge", item.motor)
        assertEquals(QueueStatus.PENDENTE, item.status)
        assertEquals(1, QueueManager.contarPendentes())
    }

    @Test
    fun moverReordersItemsAndIgnoresInvalidIndexes() {
        val first = QueueManager.adicionar("first.epub", "/tmp/first.epub")
        val second = QueueManager.adicionar("second.epub", "/tmp/second.epub")

        QueueManager.mover(0, 1)

        assertSame(second, QueueManager.items[0])
        assertSame(first, QueueManager.items[1])

        QueueManager.mover(-1, 0)
        QueueManager.mover(0, 5)

        assertSame(second, QueueManager.items[0])
        assertSame(first, QueueManager.items[1])
    }

    @Test
    fun proximoPendenteSkipsProcessedItems() {
        val first = QueueManager.adicionar("first.epub", "/tmp/first.epub")
        val second = QueueManager.adicionar("second.epub", "/tmp/second.epub")

        QueueManager.marcarProcessando(first.id)

        assertSame(second, QueueManager.proximoPendente())
    }

    @Test
    fun limparNaoAtivosKeepsPendingAndProcessingItems() {
        val pending = QueueManager.adicionar("pending.epub", "/tmp/pending.epub")
        val processing = QueueManager.adicionar("processing.epub", "/tmp/processing.epub")
        val completed = QueueManager.adicionar("completed.epub", "/tmp/completed.epub")
        val failed = QueueManager.adicionar("failed.epub", "/tmp/failed.epub")

        QueueManager.marcarProcessando(processing.id)
        QueueManager.marcarConcluido(completed.id)
        QueueManager.marcarErro(failed.id, "network")
        QueueManager.limparNaoAtivos()

        assertEquals(listOf(pending, processing), QueueManager.items)
        assertNull(QueueManager.items.find { it.id == failed.id })
    }

    @Test
    fun statusTransitionsStoreProgressAndTimestamps() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub")

        QueueManager.marcarProcessando(item.id, "gemini")
        QueueManager.atualizarProgresso(item.id, 140)

        assertEquals(QueueStatus.PROCESSANDO, item.status)
        assertEquals("gemini", item.motor)
        assertEquals(100, item.progressoPct)

        QueueManager.marcarConcluido(item.id, "/tmp/book.mp3")

        assertEquals(QueueStatus.CONCLUIDO, item.status)
        assertEquals(100, item.progressoPct)
        assertEquals("/tmp/book.mp3", item.caminhoSaida)
    }

    @Test
    fun retryFailedItemReturnsItToPending() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub")

        QueueManager.marcarProcessando(item.id, "edge")
        QueueManager.marcarErro(item.id, "network")
        QueueManager.repetir(item.id)

        assertEquals(QueueStatus.PENDENTE, item.status)
        assertEquals(0, item.progressoPct)
        assertNull(item.erro)
        assertNull(item.iniciadoEmMillis)
        assertNull(item.finalizadoEmMillis)
    }

    @Test
    fun jsonRoundTripKeepsHistoryFields() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub", "edge")
        QueueManager.marcarProcessando(item.id, "edge")
        QueueManager.atualizarProgresso(item.id, 45)
        QueueManager.marcarErro(item.id, "Sem conexao")

        val json = QueueManager.toJson()
        QueueManager.items.clear()
        QueueManager.carregarJson(json)

        val restored = QueueManager.items.single()
        assertEquals(item.id, restored.id)
        assertEquals(QueueStatus.ERRO, restored.status)
        assertEquals("Sem conexao", restored.erro)
        assertEquals("edge", restored.motor)
        assertEquals(45, restored.progressoPct)
    }

    @Test
    fun loadingProcessingItemMarksItRetryableAfterAppRestart() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub", "edge")
        QueueManager.marcarProcessando(item.id, "edge")
        val json = QueueManager.toJson()

        QueueManager.items.clear()
        QueueManager.carregarJson(json)

        val restored = QueueManager.items.single()
        assertEquals(QueueStatus.ERRO, restored.status)
        assertEquals(
            com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA,
            restored.erro
        )
    }

    @Test
    fun runTokenProtectsAgainstStaleCallbacks() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub", "edge")
        
        QueueManager.marcarProcessando(item.id, "edge")
        val activeToken = item.runToken
        org.junit.Assert.assertNotNull(activeToken)

        // Callback with stale/different runToken should be ignored
        QueueManager.atualizarProgresso(item.id, 50, "stale_token")
        assertEquals(0, item.progressoPct)

        QueueManager.marcarConcluido(item.id, "/tmp/old.mp3", "stale_token")
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        QueueManager.marcarErro(item.id, "Some error", "stale_token")
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        // Callback with correct runToken or null runToken should work
        QueueManager.atualizarProgresso(item.id, 50, activeToken)
        assertEquals(50, item.progressoPct)

        QueueManager.marcarConcluido(item.id, "/tmp/new.mp3", activeToken)
        assertEquals(QueueStatus.CONCLUIDO, item.status)
        assertEquals("/tmp/new.mp3", item.caminhoSaida)
    }

    @Test
    fun repetirClearsRunToken() {
        val item = QueueManager.adicionar("book.epub", "/tmp/book.epub", "edge")
        QueueManager.marcarProcessando(item.id, "edge")
        org.junit.Assert.assertNotNull(item.runToken)

        QueueManager.repetir(item.id)
        assertNull(item.runToken)
    }
}
