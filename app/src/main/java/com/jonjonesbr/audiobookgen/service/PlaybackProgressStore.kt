package com.jonjonesbr.audiobookgen.service

import android.content.Context

/**
 * Persiste a posição de reprodução por audiolivro, permitindo retomar de onde parou
 * e exibir o percentual já ouvido na biblioteca.
 *
 * A chave é o `caminho` (URI string ou caminho de arquivo) — o mesmo valor que
 * [AudioPlayerService.iniciar] recebe e que a biblioteca usa em `item.uri.toString()`.
 * Formato armazenado: "posicaoMs|duracaoMs".
 */
object PlaybackProgressStore {
    private const val PREFS = "playback_progress"

    private const val PROGRESSO_PCT_MAXIMO = 100

    data class Progress(val positionMs: Int, val durationMs: Int)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, caminho: String, positionMs: Int, durationMs: Int) {
        if (caminho.isBlank() || durationMs <= 0 || positionMs < 0) return
        prefs(ctx).edit().putString(caminho, "$positionMs|$durationMs").apply()
    }

    fun load(ctx: Context, caminho: String): Progress? {
        if (caminho.isBlank()) return null
        val raw = prefs(ctx).getString(caminho, null)
        val parts = raw?.split("|")
        val pos = parts?.getOrNull(0)?.toIntOrNull()
        val dur = parts?.getOrNull(1)?.toIntOrNull()
        return if (pos != null && dur != null) Progress(pos, dur) else null
    }

    fun clear(ctx: Context, caminho: String) {
        if (caminho.isBlank()) return
        prefs(ctx).edit().remove(caminho).apply()
    }

    /** Registra o instante em que a reprodução de [caminho] foi pausada (para auto-rewind). */
    fun markPaused(ctx: Context, caminho: String) {
        if (caminho.isBlank()) return
        prefs(ctx).edit().putLong(pausedAtKey(caminho), System.currentTimeMillis()).apply()
    }

    /** Instante (epoch ms) da última pausa registrada de [caminho], ou 0 se nunca pausado. */
    fun pausedAtMs(ctx: Context, caminho: String): Long {
        if (caminho.isBlank()) return 0L
        return prefs(ctx).getLong(pausedAtKey(caminho), 0L)
    }

    private fun pausedAtKey(caminho: String) = "$caminho:pausedAt"

    /** Percentual ouvido (0..100), ou 0 se desconhecido. */
    fun percent(ctx: Context, caminho: String): Int {
        val p = load(ctx, caminho)
        if (p == null || p.durationMs <= 0) return 0
        return ((p.positionMs.toLong() * PROGRESSO_PCT_MAXIMO) / p.durationMs).toInt().coerceIn(0, PROGRESSO_PCT_MAXIMO)
    }

    /** Progresso de todos os audiolivros (exclui o instante de pausa), para backup (Fase 5). */
    fun exportarTudo(ctx: Context): Map<String, Progress> =
        prefs(ctx).all.entries
            .filter { !it.key.endsWith(":pausedAt") }
            .mapNotNull { (caminho, valor) ->
                val partes = (valor as? String)?.split("|") ?: return@mapNotNull null
                val pos = partes.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val dur = partes.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                caminho to Progress(pos, dur)
            }
            .toMap()

    /** Importa de um backup, mesclando de forma conservadora: nunca regride uma posição já mais avançada. */
    fun importarMerge(ctx: Context, entradas: Map<String, Progress>) {
        entradas.forEach { (caminho, importado) ->
            val local = load(ctx, caminho)
            if (local == null || importado.positionMs > local.positionMs) {
                save(ctx, caminho, importado.positionMs, importado.durationMs)
            }
        }
    }
}
