package com.jonjonesbr.audiobookgen.data

import android.content.Context
import org.json.JSONArray

/**
 * Termos da blacklist (palavras/trechos ignorados na síntese), persistidos como JSON em
 * [AppPrefs.blacklistWordsJson]. Centraliza a lógica que estava duplicada (parsing de JSON
 * inline) entre SettingsActivity e ReaderViewModel.
 */
class BlacklistStore(context: Context) {

    private val appPrefs = AppPrefs(context)

    fun load(): List<String> = parse(appPrefs.blacklistWordsJson)

    fun count(): Int = load().size

    fun save(termos: List<String>) {
        appPrefs.blacklistWordsJson = serialize(termos)
    }

    /** Adiciona o termo (após trim) se não vazio e ainda não presente. Retorna true se adicionou. */
    fun add(termo: String): Boolean {
        val t = termo.trim()
        val atual = load()
        if (t.isEmpty() || atual.contains(t)) return false
        save(atual + t)
        return true
    }

    fun remove(termo: String) {
        save(load().filterNot { it == termo })
    }

    companion object {
        fun parse(json: String): List<String> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        fun serialize(termos: List<String>): String = JSONArray(termos).toString()
    }
}
