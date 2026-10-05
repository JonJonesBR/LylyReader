package com.jonjonesbr.audiobookgen.service

import android.content.Context

/**
 * Persiste a última voz/motor/velocidade usados na leitura guiada de cada livro (perfil por
 * documento), permitindo reaplicar ao reabrir em vez de depender só das preferências globais.
 *
 * A chave é o `caminho` do livro — mesmo padrão de [PlaybackProgressStore]. Formato
 * armazenado: "voz|motor|velocidade".
 */
object GuidedBookPrefsStore {
    private const val PREFS = "guided_book_prefs"

    data class BookPrefs(val voz: String, val motor: String, val speedMult: Float)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, caminho: String, voz: String, motor: String, speedMult: Float) {
        if (caminho.isBlank() || voz.isBlank() || motor.isBlank()) return
        prefs(ctx).edit().putString(caminho, "$voz|$motor|$speedMult").apply()
    }

    fun load(ctx: Context, caminho: String): BookPrefs? {
        if (caminho.isBlank()) return null
        val parts = prefs(ctx).getString(caminho, null)?.split("|") ?: emptyList()
        val voz = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val motor = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val speedMult = parts.getOrNull(2)?.toFloatOrNull()
        return if (voz != null && motor != null && speedMult != null) {
            BookPrefs(voz, motor, speedMult)
        } else {
            null
        }
    }

    /** Remove o perfil de um livro — chamado quando o audiolivro é apagado da biblioteca. */
    fun remover(ctx: Context, caminho: String) {
        prefs(ctx).edit().remove(caminho).apply()
    }

    /** Migração de caminho (livro foi para a biblioteca): copia o perfil para [novo] sem sobrescrever o que já existe. */
    fun renomear(ctx: Context, antigo: String, novo: String) {
        val valor = prefs(ctx).getString(antigo, null) ?: return
        if (antigo != novo && !prefs(ctx).contains(novo)) prefs(ctx).edit().putString(novo, valor).apply()
    }

    /** Todos os perfis salvos (caminho -> "voz|motor|velocidade"), para backup. */
    fun exportarTudo(ctx: Context): Map<String, String> =
        prefs(ctx).all.mapNotNull { (chave, valor) -> (valor as? String)?.let { chave to it } }.toMap()

    /** Importa de um backup, sem sobrescrever perfis locais já existentes pro mesmo livro. */
    fun importarMerge(ctx: Context, entradas: Map<String, String>) {
        val local = prefs(ctx)
        entradas.forEach { (caminho, valor) ->
            if (!local.contains(caminho)) prefs(ctx).edit().putString(caminho, valor).apply()
        }
    }
}
