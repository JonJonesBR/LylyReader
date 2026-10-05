package com.jonjonesbr.audiobookgen.util

/**
 * Converte o ritmo de narração (porcentagem de velocidade, base 100 — ex.: 90 → "-10%",
 * 110 → "+10%") no formato de taxa esperado pelos motores Edge/Gemini TTS.
 *
 * Centraliza a fórmula que antes estava duplicada em MainActivity, ReaderActivity,
 * GuidedPlayerManager e AudiobookViewModel (e onde um bug de base — 87 vs 100 — já apareceu).
 */
object TtsRate {
    /** Base percentual do ritmo (100 = velocidade normal). */
    private const val RITMO_BASE = 100

    fun fromRitmo(ritmo: Int): String {
        val diff = ritmo - RITMO_BASE
        return if (diff >= 0) "+${diff}%" else "${diff}%"
    }
}
