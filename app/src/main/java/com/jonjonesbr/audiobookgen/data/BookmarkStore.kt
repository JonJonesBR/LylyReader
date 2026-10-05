package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ChavesDeLivro
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BookmarkStore {
    private const val PREFS = "audiobookgen_prefs"
    private const val KEY = "bookmarks"

    data class Bookmark(
        val id: String,
        val caminhoLivro: String,
        val nome: String,
        val indiceParagrafo: Int,
        val ts: Long
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun adicionar(ctx: Context, caminhoLivro: String, nome: String, indiceParagrafo: Int) {
        if (caminhoLivro.isBlank()) return
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            caminhoLivro = caminhoLivro,
            nome = nome,
            indiceParagrafo = indiceParagrafo,
            ts = System.currentTimeMillis()
        )
        val atuais = loadAll(ctx)
        save(ctx, atuais + bookmark)
    }

    fun remover(ctx: Context, id: String) {
        save(ctx, loadAll(ctx).filter { it.id != id })
    }

    /** Migração de caminho (livro foi para a biblioteca): os marcadores passam a apontar para [novo]. */
    fun renomearLivro(ctx: Context, antigo: String, novo: String) {
        val atuais = loadAll(ctx)
        val migrados = ChavesDeLivro.migrarLista(atuais, antigo, novo, { it.caminhoLivro }) { b, c -> b.copy(caminhoLivro = c) }
        if (migrados != atuais) save(ctx, migrados)
    }

    /** Apaga os marcadores do livro (livro removido da biblioteca). */
    fun removerDoLivro(ctx: Context, caminhoLivro: String) {
        val atuais = loadAll(ctx)
        val restantes = atuais.filter { it.caminhoLivro != caminhoLivro }
        if (restantes.size != atuais.size) save(ctx, restantes)
    }

    fun listarPara(ctx: Context, caminhoLivro: String): List<Bookmark> =
        loadAll(ctx).filter { it.caminhoLivro == caminhoLivro }

    fun loadAll(ctx: Context): List<Bookmark> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            paraLista(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** JSON com todos os marcadores, para backup completo (Fase 5). */
    fun paraJson(ctx: Context): String = paraJsonArray(loadAll(ctx)).toString()

    /** Importa de um backup, mesclando: só adiciona marcadores cujo id ainda não existe localmente. */
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

    private fun paraLista(arr: JSONArray): List<Bookmark> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            if (id.isBlank()) null else Bookmark(
                id = id,
                caminhoLivro = o.optString("caminhoLivro", ""),
                nome = o.optString("nome", ""),
                indiceParagrafo = o.optInt("indiceParagrafo", 0),
                ts = o.optLong("ts", 0L)
            )
        }

    private fun paraJsonArray(list: List<Bookmark>): JSONArray {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("caminhoLivro", it.caminhoLivro)
                .put("nome", it.nome)
                .put("indiceParagrafo", it.indiceParagrafo)
                .put("ts", it.ts))
        }
        return arr
    }

    private fun save(ctx: Context, list: List<Bookmark>) {
        prefs(ctx).edit().putString(KEY, paraJsonArray(list).toString()).apply()
    }
}
