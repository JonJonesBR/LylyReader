package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.service.CompletionOutcome
import com.jonjonesbr.audiobookgen.service.ConversionQueueCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionQueueCoordinatorTest {
    @Test
    fun startNextMarksFirstPendingAsProcessing() {
        val first = QueueItem("1", "first.epub", "/tmp/first.epub")
        val second = QueueItem("2", "second.epub", "/tmp/second.epub")
        val coordinator = ConversionQueueCoordinator(mutableListOf(first, second))

        val start = coordinator.startNext("edge")

        assertSame(first, start?.item)
        assertEquals(1, start?.position)
        assertEquals(2, start?.total)
        assertEquals("first.mp3", start?.outputFileName)
        assertEquals(QueueStatus.PROCESSANDO, first.status)
        assertEquals("edge", first.motor)
        assertTrue(coordinator.isProcessingQueue)
    }

    @Test
    fun completeCurrentClearsCurrentAndReportsPendingItems() {
        val first = QueueItem("1", "first.epub", "/tmp/first.epub")
        val second = QueueItem("2", "second.epub", "/tmp/second.epub")
        val coordinator = ConversionQueueCoordinator(mutableListOf(first, second))

        val start = coordinator.startNext("edge")
        val token = start!!.runToken
        val outcome = coordinator.completeCurrent("/tmp/first.mp3", token)

        assertEquals(CompletionOutcome.ACEITO_TEM_PROXIMO, outcome)
        assertFalse(coordinator.isProcessingQueue)
        assertNull(coordinator.currentItem)
        assertEquals(QueueStatus.CONCLUIDO, first.status)
        assertEquals("/tmp/first.mp3", first.caminhoSaida)
    }

    @Test
    fun failCurrentMarksItemAsRetryableError() {
        val item = QueueItem("1", "book.epub", "/tmp/book.epub")
        val coordinator = ConversionQueueCoordinator(mutableListOf(item))

        val start = coordinator.startNext("gemini")
        val token = start!!.runToken
        coordinator.updateProgress(150, token)
        coordinator.failCurrent("Sem conexao", token)

        assertEquals(QueueStatus.ERRO, item.status)
        assertEquals("Sem conexao", item.erro)
        assertEquals(100, item.progressoPct)
        assertFalse(coordinator.isProcessingQueue)
    }

    @Test
    fun runTokenProtectsCoordinatorFromStaleCallbacks() {
        val item = QueueItem("1", "book.epub", "/tmp/book.epub")
        val coordinator = ConversionQueueCoordinator(mutableListOf(item))

        val start = coordinator.startNext("gemini")
        val activeToken = start?.runToken
        org.junit.Assert.assertNotNull(activeToken)

        // Stale runToken should be ignored (returns false, no state change)
        assertFalse(coordinator.updateProgress(50, "stale_token"))
        assertEquals(0, item.progressoPct)

        assertEquals(CompletionOutcome.REJEITADO_TOKEN, coordinator.completeCurrent("/tmp/out.mp3", "stale_token"))
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        assertFalse(coordinator.failCurrent("Stale error", "stale_token"))
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        // null token should be rejected when item has a token (returns false)
        assertFalse(coordinator.updateProgress(50, null))
        assertEquals(0, item.progressoPct)

        assertEquals(CompletionOutcome.REJEITADO_TOKEN, coordinator.completeCurrent("/tmp/out.mp3", null))
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        assertFalse(coordinator.failCurrent("Stale error", null))
        assertEquals(QueueStatus.PROCESSANDO, item.status)

        // Valid runToken should work
        assertTrue(coordinator.updateProgress(50, activeToken))
        assertEquals(50, item.progressoPct)

        assertEquals(CompletionOutcome.ACEITO_FILA_VAZIA, coordinator.completeCurrent("/tmp/out.mp3", activeToken))
        assertEquals(QueueStatus.CONCLUIDO, item.status)
    }

    @Test
    fun updateProgressReturnsFalseForSamePct() {
        val item = QueueItem("1", "book.epub", "/tmp/book.epub")
        val coordinator = ConversionQueueCoordinator(mutableListOf(item))
        val start = coordinator.startNext("edge")
        val token = start!!.runToken

        assertTrue(coordinator.updateProgress(50, token))
        assertEquals(50, item.progressoPct)

        // second call with same pct returns false
        assertFalse(coordinator.updateProgress(50, token))
        assertEquals(50, item.progressoPct)
    }
}
