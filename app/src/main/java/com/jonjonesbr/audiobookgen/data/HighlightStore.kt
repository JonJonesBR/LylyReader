package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ChavesDeLivro
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Destaques (highlights) de trechos de texto na leitura (T3.5). Mesmo padrão de
 * armazenamento do [BookmarkStore] (SharedPreferences + JSON) — lista pequena por livro,
 * não justifica Room.
 */
object HighlightStore {
    private const val PREFS = "audiobookgen_prefs"
    private const val KEY = "highlights"

    data class Highlight(
        val id: String,
        val caminhoLivro: String,
        val indiceParagrafo: Int,
        val inicio: Int,
        val fim: Int,
        val nota: String,
        val ts: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * [trecho] é o intervalo de char offsets dentro do texto do parágrafo, EXCLUSIVO no fim
     * (construído com `inicio until fim`, no padrão de `String.substring`/`selectionEnd` do
     * Android) — NÃO um IntRange inclusive literal, que perderia o último caractere da marca.
     */
    fun adicionar(
        ctx: Context,
        caminhoLivro: String,
        indiceParagrafo: Int,
        trecho: IntRange,
        nota: String = ""
    ) {
        if (caminhoLivro.isBlank() || trecho.isEmpty()) return
        val highlight = Highlight(
            id = UUID.randomUUID().toString(),
            caminhoLivro = caminhoLivro,
            indiceParagrafo = indiceParagrafo,
            inicio = trecho.first,
            fim = trecho.last + 1,
            nota = nota,
            ts = System.currentTimeMillis()
        )
        val atuais = loadAll(ctx)
        save(ctx, atuais + highlight)
    }

    fun remover(ctx: Context, id: String) {
        save(ctx, loadAll(ctx).filter { it.id != id })
    }

    /** Migração de caminho (livro foi para a biblioteca): os destaques passam a apontar para [novo]. */
    fun renomearLivro(ctx: Context, antigo: String, novo: String) {
        val atuais = loadAll(ctx)
        val migrados = ChavesDeLivro.migrarLista(atuais, antigo, novo, { it.caminhoLivro }) { h, c -> h.copy(caminhoLivro = c) }
        if (migrados != atuais) save(ctx, migrados)
    }

    /** Apaga os destaques do livro (livro removido da biblioteca). */
    fun removerDoLivro(ctx: Context, caminhoLivro: String) {
        val atuais = loadAll(ctx)
        val restantes = atuais.filter { it.caminhoLivro != caminhoLivro }
        if (restantes.size != atuais.size) save(ctx, restantes)
    }

    fun listarPara(ctx: Context, caminhoLivro: String): List<Highlight> =
        loadAll(ctx).filter { it.caminhoLivro == caminhoLivro }

    fun loadAll(ctx: Context): List<Highlight> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            paraLista(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** JSON com todos os destaques, para backup completo (Fase 5). */
    fun paraJson(ctx: Context): String = paraJsonArray(loadAll(ctx)).toString()

    /** Importa de um backup, mesclando: só adiciona destaques cujo id ainda não existe localmente. */
    fun importarMerge(ctx: Context, json: String) {
        val importados = try {
            paraLista(JSONArray(json))
        } catch (_: Exception) {
            return
        }
        val atuais = loadAll(ctx)
        val idsAtuais = atuais.map { it.id }.toSet()
        val novos = importados.filter { it.id !in idsAtuais }
        if (novos.isNotEmpty()) save(ctx, atuais + novos)
    }

    private fun paraLista(arr: JSONArray): List<Highlight> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) null else Highlight(
                id = id,
                caminhoLivro = o.optString("caminhoLivro", ""),
                indiceParagrafo = o.optInt("indiceParagrafo", 0),
                inicio = o.optInt("inicio", 0),
                fim = o.optInt("fim", 0),
                nota = o.optString("nota", ""),
                ts = o.optLong("ts", 0L)
            )
        }

    private fun paraJsonArray(list: List<Highlight>): JSONArray {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("caminhoLivro", it.caminhoLivro)
                    .put("indiceParagrafo", it.indiceParagrafo)
                    .put("inicio", it.inicio)
                    .put("fim", it.fim)
                    .put("nota", it.nota)
                    .put("ts", it.ts)
            )
        }
        return arr
    }

    private fun save(ctx: Context, list: List<Highlight>) {
        prefs(ctx).edit().putString(KEY, paraJsonArray(list).toString()).apply()
    }
}
