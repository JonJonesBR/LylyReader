package com.jonjonesbr.audiobookgen.tts

/**
 * Política do teto do cache de áudio das vozes: quando o total passa do teto, apaga os arquivos
 * usados há mais tempo até voltar para uma fração do teto (folga, para não limpar a cada síntese).
 * Função pura: recebe a lista de arquivos e devolve quais remover.
 */
object CachePolicy {
    data class Arquivo(val nome: String, val tamanho: Long, val ultimoUso: Long)

    /** Valores oferecidos ao usuário em Ajustes (MB); 0 = sem limite. */
    val TETOS_MB: List<Int> = listOf(256, 512, 1024, 2048, 4096, 0)
    const val TETO_PADRAO_MB = 1024

    /** Depois de passar do teto, a limpeza mira nesta fração dele. */
    const val FRACAO_ALVO = 0.8

    /** Arquivos usados há menos que isso nunca são removidos (podem estar tocando ou prestes a tocar). */
    const val PROTEGER_RECENTES_MS = 10 * 60 * 1000L

    fun selecionarParaRemover(
        arquivos: List<Arquivo>,
        tetoBytes: Long,
        agora: Long,
        protegerMs: Long = PROTEGER_RECENTES_MS
    ): List<Arquivo> {
        if (tetoBytes <= 0L) return emptyList()
        var total = arquivos.sumOf { it.tamanho }
        if (total <= tetoBytes) return emptyList()
        val alvo = (tetoBytes * FRACAO_ALVO).toLong()
        val remover = mutableListOf<Arquivo>()
        for (arquivo in arquivos.sortedBy { it.ultimoUso }) {
            if (total <= alvo) break
            if (agora - arquivo.ultimoUso < protegerMs) continue
            remover += arquivo
            total -= arquivo.tamanho
        }
        return remover
    }

    fun tetoEmBytes(tetoMb: Int): Long = if (tetoMb <= 0) 0L else tetoMb * 1024L * 1024L
}
