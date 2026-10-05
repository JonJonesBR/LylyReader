package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueItemEntity
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.data.toEntity
import com.jonjonesbr.audiobookgen.data.toQueueItem
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueItemEntityTest {
    @Test
    fun mapsQueueItemToEntityAndBack() {
        val item = QueueItem(
            id = "id-1",
            nome = "book.epub",
            caminhoLocal = "/tmp/book.epub",
            status = QueueStatus.ERRO,
            erro = "Sem conexao",
            motor = "edge",
            progressoPct = 45,
            criadoEmMillis = 10,
            iniciadoEmMillis = 20,
            finalizadoEmMillis = 30,
            caminhoSaida = "/tmp/book.mp3",
        )

        val restored = item.toEntity().toQueueItem()

        assertEquals(item, restored)
    }

    @Test
    fun mapsInterruptedProcessingItemToRetryableError() {
        val entity = QueueItemEntity(
            id = "id-1",
            nome = "book.epub",
            caminhoLocal = "/tmp/book.epub",
            status = QueueStatus.PROCESSANDO.name,
            erro = null,
            motor = "gemini",
            progressoPct = 30,
            criadoEmMillis = 10,
            iniciadoEmMillis = 20,
            finalizadoEmMillis = null,
            caminhoSaida = null,
        )

        val restored = entity.toQueueItem()

        assertEquals(QueueStatus.ERRO, restored.status)
        assertEquals(
            com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA,
            restored.erro
        )
    }
}
