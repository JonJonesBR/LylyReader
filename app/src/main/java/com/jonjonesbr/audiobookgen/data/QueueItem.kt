package com.jonjonesbr.audiobookgen.data

import java.util.UUID

/** Marcador do erro "interrompida": a tela traduz (a camada de dados não tem Context). */
const val ERRO_FILA_INTERROMPIDA = "@interrompida"

data class QueueItem(
    val id: String,
    val nome: String,
    val caminhoLocal: String,
    var status: QueueStatus = QueueStatus.PENDENTE,
    var erro: String? = null,
    var motor: String? = null,
    var progressoPct: Int = 0,
    val criadoEmMillis: Long = System.currentTimeMillis(),
    var iniciadoEmMillis: Long? = null,
    var finalizadoEmMillis: Long? = null,
    var caminhoSaida: String? = null,
    var runToken: String? = null,
)
