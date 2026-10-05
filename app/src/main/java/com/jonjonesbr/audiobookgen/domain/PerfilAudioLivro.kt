package com.jonjonesbr.audiobookgen.domain

import org.json.JSONObject

/**
 * Ajustes de áudio da leitura guiada que um livro pode ter só para si (opção "Só neste livro" em Voz e motor).
 * Sem perfil, o livro usa os ajustes globais de Configurações › Áudio.
 */
data class PerfilAudioLivro(
    /** Tom da voz em meios-tons (−6…+6 = ±3 semitons). */
    val tomMeiosTons: Int = 0,
    /** Suavização de agudos em dB. */
    val agudosDb: Int = 0,
    /** Mudança extra de tom, em meios-tons, só nos trechos de fala/citação (0 = desligado). */
    val tomNasFalasMeiosTons: Int = 0,
    val pausaFinalFraseMs: Int = 500,
    /** Unidades preparadas à frente; −1 = automático. */
    val bufferAdiante: Int = -1
) {
    fun toJson(): String = JSONObject()
        .put("tom", tomMeiosTons).put("agudos", agudosDb).put("tomFalas", tomNasFalasMeiosTons)
        .put("pausa", pausaFinalFraseMs).put("buffer", bufferAdiante).toString()

    companion object {
        fun fromJson(json: String?): PerfilAudioLivro? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                PerfilAudioLivro(
                    tomMeiosTons = o.optInt("tom", 0),
                    agudosDb = o.optInt("agudos", 0),
                    tomNasFalasMeiosTons = o.optInt("tomFalas", 0),
                    pausaFinalFraseMs = o.optInt("pausa", 500),
                    bufferAdiante = o.optInt("buffer", -1)
                )
            }.getOrNull()
        }
    }
}

/** Decide se um trecho do parágrafo (uma oração) é fala de personagem ou citação, para o tom das falas. */
object FalaNoTrecho {
    /** Fração (0…1) do trecho [ini, ini+tamanho) que cai em fala; as posições são em caracteres das orações. */
    fun fracaoDeFala(partes: List<OfflineSpeakerAttributor.ParagraphPart>, ini: Int, tamanho: Int, totalOracoes: Int): Float {
        val total = partes.sumOf { it.text.length }
        if (total <= 0 || tamanho <= 0 || totalOracoes <= 0) return 0f
        val escala = total.toFloat() / totalOracoes
        val a = ini * escala
        val b = (ini + tamanho) * escala
        var cursor = 0f
        var emFala = 0f
        for (parte in partes) {
            val fim = cursor + parte.text.length
            if (parte.speech) emFala += (minOf(fim, b) - maxOf(cursor, a)).coerceAtLeast(0f)
            cursor = fim
        }
        return (emFala / (b - a)).coerceIn(0f, 1f)
    }

    /** Fala quando mais da metade do trecho está entre travessões/aspas. */
    fun ehFala(partes: List<OfflineSpeakerAttributor.ParagraphPart>, ini: Int, tamanho: Int, totalOracoes: Int): Boolean =
        fracaoDeFala(partes, ini, tamanho, totalOracoes) > 0.5f
}
