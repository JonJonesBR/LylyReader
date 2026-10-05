package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.CapituloAudio
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persiste os timestamps de capítulo (ms) de um audiolivro convertido, para navegação
 * anterior/próximo capítulo no player (T4.4).
 *
 * A chave é o `caminho` — mesmo valor e mesma convenção de [PlaybackProgressStore]. Só existe
 * entrada para livros com múltiplos capítulos detectados (ex.: EPUB); os demais simplesmente
 * não têm chave, e [carregar] retorna lista vazia.
 */
object ChapterMarksStore {
    private const val PREFS = "chapter_marks"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Serializa a lista de capítulos para o formato JSON persistido (usado também pelo Worker). */
    fun paraJson(capitulos: List<CapituloAudio>): String {
        val arr = JSONArray()
        capitulos.forEach {
            arr.put(JSONObject().put("titulo", it.titulo).put("inicio_ms", it.inicioMs).put("fim_ms", it.fimMs))
        }
        return arr.toString()
    }

    /** Salva o JSON já serializado (vindo do outputData do Worker). [json] nulo/vazio remove a chave. */
    fun salvarJson(ctx: Context, caminho: String, json: String?) {
        if (caminho.isBlank()) return
        if (json.isNullOrBlank() || json == "[]") {
            prefs(ctx).edit().remove(caminho).apply()
            return
        }
        prefs(ctx).edit().putString(caminho, json).apply()
    }

    fun carregar(ctx: Context, caminho: String): List<CapituloAudio> {
        val raw = if (caminho.isBlank()) null else prefs(ctx).getString(caminho, null)
        return try {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val titulo = o.optString("titulo", "")
                val inicioMs = o.optLong("inicio_ms", -1L)
                val fimMs = o.optLong("fim_ms", -1L)
                if (titulo.isBlank() || inicioMs < 0 || fimMs < 0) null
                else CapituloAudio(titulo, inicioMs, fimMs)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Remove os capítulos de um livro — chamado quando o audiolivro é apagado da biblioteca. */
    fun remover(ctx: Context, caminho: String) {
        prefs(ctx).edit().remove(caminho).apply()
    }

    /** Todos os capítulos salvos (caminho -> JSON já serializado), para backup. */
    fun exportarTudo(ctx: Context): Map<String, String> =
        prefs(ctx).all.mapNotNull { (chave, valor) -> (valor as? String)?.let { chave to it } }.toMap()

    /** Importa de um backup, sem sobrescrever capítulos locais já existentes pro mesmo livro. */
    fun importarMerge(ctx: Context, entradas: Map<String, String>) {
        val local = prefs(ctx)
        entradas.forEach { (caminho, json) -> if (!local.contains(caminho)) salvarJson(ctx, caminho, json) }
    }
}
