package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura do limite de atualização de progresso da conversão (notificação e setProgressAsync).
 * Sem Android: recebe os instantes em milissegundos.
 */
object LimiteDeProgresso {

    /** Intervalo mínimo entre atualizações intermediárias (1 s). */
    const val INTERVALO_PADRAO_MS = 1000L

    /**
     * Deve atualizar quando: é o primeiro progresso; ou o intervalo já passou (inclusive);
     * ou é o início (pct <= 0) ou o fim (pct >= 95), que nunca são descartados.
     */
    fun deveAtualizar(
        ultimoMs: Long?,
        agoraMs: Long,
        pct: Int,
        intervaloMs: Long = INTERVALO_PADRAO_MS
    ): Boolean =
        ultimoMs == null ||
            agoraMs - ultimoMs >= intervaloMs ||
            pct <= 0 ||
            pct >= 95
}
