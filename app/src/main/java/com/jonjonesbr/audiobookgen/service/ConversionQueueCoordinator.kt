package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.domain.ExportStatus
import com.jonjonesbr.audiobookgen.data.QueueItem
import java.util.UUID
import java.io.File

private fun novoRunToken(): String = UUID.randomUUID().toString()

class ConversionQueueCoordinator(
    private val items: MutableList<QueueItem> = QueueManager.items,
) {
    companion object {
        /** Percentual máximo de progresso (0..100). */
        private const val PROGRESSO_PCT_MAXIMO = 100
    }

    var currentItem: QueueItem? = null
        private set

    val isProcessingQueue: Boolean
        get() = currentItem != null

    /** Um item com token só aceita callbacks que apresentem o MESMO token.
     *  Itens sem token (estado restaurado) aceitam qualquer callback. */
    private fun tokenValido(itemToken: String?, recebido: String?): Boolean =
        itemToken == null || recebido == itemToken

    fun startNext(motor: String): QueueStart? {
        val next = items.firstOrNull { it.status == QueueStatus.PENDENTE } ?: run {
            currentItem = null
            return null
        }

        currentItem = next
        markProcessing(next, motor)

        return QueueStart(
            item = next,
            position = items.indexOf(next) + 1,
            total = items.size,
            outputFileName = "${File(next.nome).nameWithoutExtension}.mp3",
            runToken = next.runToken!!,
        )
    }

    fun updateProgress(progressPct: Int, runToken: String? = null): Boolean {
        val item = currentItem
        if (item == null || !tokenValido(item.runToken, runToken)) return false
        val pct = progressPct.coerceIn(0, PROGRESSO_PCT_MAXIMO)
        return if (item.progressoPct != pct) {
            item.progressoPct = pct
            true
        } else {
            false
        }
    }

    fun completeCurrent(outputPath: String, runToken: String? = null): CompletionOutcome {
        currentItem?.let { item ->
            if (!tokenValido(item.runToken, runToken)) return CompletionOutcome.REJEITADO_TOKEN
            item.status = QueueStatus.CONCLUIDO
            item.progressoPct = PROGRESSO_PCT_MAXIMO
            item.erro = null
            item.caminhoSaida = outputPath
            item.finalizadoEmMillis = System.currentTimeMillis()
        }
        currentItem = null
        return if (hasPending()) {
            CompletionOutcome.ACEITO_TEM_PROXIMO
        } else {
            CompletionOutcome.ACEITO_FILA_VAZIA
        }
    }

    fun failCurrent(message: String, runToken: String? = null): Boolean {
        currentItem?.let { item ->
            if (!tokenValido(item.runToken, runToken)) return false
            item.status = QueueStatus.ERRO
            item.erro = message
            item.finalizadoEmMillis = System.currentTimeMillis()
        }
        currentItem = null
        return true
    }

    // resetCurrent apenas redefine currentItem, preservando runToken caso seja chamada durante uma transição
    // limpar runToken deve ocorrer apenas ao reprocessar o item (repetir no QueueManager)
    fun resetCurrent() {
        currentItem = null
    }

    /** Reconhece como atual o item que a fila está processando (o worker pode ter fechado um e iniciado outro sem a tela). */
    fun sincronizar() {
        currentItem = items.firstOrNull { it.status == QueueStatus.PROCESSANDO }
    }

    fun hasPending(): Boolean = items.any { it.status == QueueStatus.PENDENTE }

    private fun markProcessing(item: QueueItem, motor: String) {
        item.status = QueueStatus.PROCESSANDO
        item.motor = motor
        item.progressoPct = 0
        item.erro = null
        item.iniciadoEmMillis = System.currentTimeMillis()
        item.finalizadoEmMillis = null
        item.runToken = novoRunToken()
    }
}

data class QueueStart(
    val item: QueueItem,
    val position: Int,
    val total: Int,
    val outputFileName: String,
    val runToken: String,
)

enum class CompletionOutcome { ACEITO_TEM_PROXIMO, ACEITO_FILA_VAZIA, REJEITADO_TOKEN }
