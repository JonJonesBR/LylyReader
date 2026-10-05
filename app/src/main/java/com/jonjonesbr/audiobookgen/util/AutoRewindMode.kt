package com.jonjonesbr.audiobookgen.util

enum class AutoRewindMode { OFF, CURTO, LONGO }

private const val LIMIAR_PAUSA_CURTA_MS = 5 * 60_000L
private const val LIMIAR_PAUSA_LONGA_MS = 30 * 60_000L
const val AUTO_REWIND_RECUO_CURTO_MS = 10_000
const val AUTO_REWIND_RECUO_LONGO_MS = 30_000

/**
 * Recuo (em ms) a aplicar ao retomar a reprodução após uma pausa de [pausaMs].
 * Modo CURTO recua no máximo [AUTO_REWIND_RECUO_CURTO_MS]; LONGO escala para
 * [AUTO_REWIND_RECUO_LONGO_MS] em pausas de 30+ minutos, como apps como Audible.
 */
fun calcularRecuoAutoRewind(mode: AutoRewindMode, pausaMs: Long): Int {
    if (mode == AutoRewindMode.OFF || pausaMs < LIMIAR_PAUSA_CURTA_MS) return 0
    return if (mode == AutoRewindMode.LONGO && pausaMs >= LIMIAR_PAUSA_LONGA_MS) {
        AUTO_REWIND_RECUO_LONGO_MS
    } else {
        AUTO_REWIND_RECUO_CURTO_MS
    }
}
