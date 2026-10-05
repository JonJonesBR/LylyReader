package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ChavesDeLivro
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Normaliza um nome de livro pra comparação de dedupe (não pra exibição): remove espaços nas
 * pontas, ignora maiúsculas/minúsculas, e ignora um sufixo de cópia duplicada tipo " (1)"/" (2)"
 * — o padrão que downloads/importações repetidas do MESMO arquivo costumam ganhar do sistema.
 * Sem isso, reabrir o mesmo livro por uma cópia baixada de novo (ou com case diferente) criava
 * uma segunda entrada em "Continuar lendo" em vez de substituir a antiga.
 */
fun nomeNormalizadoParaDedupe(nome: String): String =
    nome.trim().lowercase().replace(Regex("\\s*\\(\\d+\\)$"), "")

/**
 * Registro persistente dos livros abertos na leitura guiada, para "Continuar lendo".
 *
 * Os documentos são cópias em `cacheDir` com nome `entrada_{ts}_{nome}` (caminho muda a cada
 * importação e o cache pode ser limpo pelo sistema) — por isso o dedupe é por **nome** e a
 * leitura filtra entradas cujo arquivo não existe mais.
 */
object RecentReadingsStore {
    private const val PREFS = "audiobookgen_prefs"
    private const val KEY = "recent_readings"
    private const val MAX = 12

    data class Entry(val caminho: String, val nome: String, val ts: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Nome de exibição a partir do caminho de cache (remove o prefixo `entrada_{ts}_` e a extensão). */
    fun nomeLimpo(caminho: String): String =
        File(caminho).name
            .replaceFirst(Regex("^entrada_\\d+_"), "")
            .substringBeforeLast('.')
            .ifBlank { File(caminho).name }

    fun record(ctx: Context, caminho: String, nome: String) {
        if (caminho.isBlank()) return
        val nomeNorm = nomeNormalizadoParaDedupe(nome)
        val atuais = loadRaw(ctx).filter {
            nomeNormalizadoParaDedupe(it.nome) != nomeNorm && it.caminho != caminho
        }
        val nova = Entry(caminho, nome, System.currentTimeMillis())
        save(ctx, (listOf(nova) + atuais).take(MAX))
    }

    fun remove(ctx: Context, caminho: String) {
        save(ctx, loadRaw(ctx).filter { it.caminho != caminho })
    }

    /** Migração de caminho (livro foi para a biblioteca): a entrada passa a apontar para [novo]. */
    fun renomear(ctx: Context, antigo: String, novo: String) {
        val atuais = loadRaw(ctx)
        val migrados = ChavesDeLivro.migrarLista(atuais, antigo, novo, { it.caminho }) { e, c -> e.copy(caminho = c) }
            .distinctBy { it.caminho }
        if (migrados != atuais) save(ctx, migrados)
    }

    /** Recentes cujos arquivos ainda existem, mais recentes primeiro. */
    fun load(ctx: Context): List<Entry> = loadRaw(ctx).filter { File(it.caminho).exists() }

    private fun loadRaw(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("caminho", "")
                if (c.isBlank()) null else Entry(c, o.optString("nome", ""), o.optLong("ts", 0L))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("caminho", it.caminho).put("nome", it.nome).put("ts", it.ts))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
