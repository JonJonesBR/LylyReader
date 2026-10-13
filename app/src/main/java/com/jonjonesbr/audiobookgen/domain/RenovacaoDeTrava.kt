package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura da renovação do WakeLock da conversão. Sem Android: recebe os instantes em milissegundos.
 */
object RenovacaoDeTrava {

    /** Intervalo padrão entre renovações (5 min), bem menor que o prazo de cada aquisição. */
    const val INTERVALO_PADRAO_MS = 5 * 60_000L

    /** Deve renovar quando o tempo desde a última renovação alcançou o intervalo (inclusive). */
    fun deveRenovar(ultimoMs: Long, agoraMs: Long, intervaloMs: Long = INTERVALO_PADRAO_MS): Boolean =
        agoraMs - ultimoMs >= intervaloMs
}
