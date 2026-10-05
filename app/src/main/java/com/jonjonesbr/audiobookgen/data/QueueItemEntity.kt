package com.jonjonesbr.audiobookgen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Percentual máximo de progresso (0..100). */
private const val PROGRESSO_PCT_MAXIMO = 100

@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val caminhoLocal: String,
    val status: String,
    val erro: String?,
    val motor: String?,
    val progressoPct: Int,
    val criadoEmMillis: Long,
    val iniciadoEmMillis: Long?,
    val finalizadoEmMillis: Long?,
    val caminhoSaida: String?,
)

fun QueueItem.toEntity(): QueueItemEntity = QueueItemEntity(
    id = id,
    nome = nome,
    caminhoLocal = caminhoLocal,
    status = status.name,
    erro = erro,
    motor = motor,
    progressoPct = progressoPct,
    criadoEmMillis = criadoEmMillis,
    iniciadoEmMillis = iniciadoEmMillis,
    finalizadoEmMillis = finalizadoEmMillis,
    caminhoSaida = caminhoSaida,
)

fun QueueItemEntity.toQueueItem(): QueueItem {
    val restoredStatus = runCatching {
        QueueStatus.valueOf(status)
    }.getOrDefault(QueueStatus.PENDENTE)
    val safeStatus = if (restoredStatus == QueueStatus.PROCESSANDO) QueueStatus.ERRO else restoredStatus

    return QueueItem(
        id = id,
        nome = nome,
        caminhoLocal = caminhoLocal,
        status = safeStatus,
        erro = erro
            ?: if (restoredStatus == QueueStatus.PROCESSANDO) {
                ERRO_FILA_INTERROMPIDA
            } else {
                null
            },
        motor = motor,
        progressoPct = progressoPct.coerceIn(0, PROGRESSO_PCT_MAXIMO),
        criadoEmMillis = criadoEmMillis,
        iniciadoEmMillis = iniciadoEmMillis,
        finalizadoEmMillis = finalizadoEmMillis,
        caminhoSaida = caminhoSaida,
    )
}
