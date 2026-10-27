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
        synchronized(trava) {
            appPrefs.blacklistWordsJson = serialize(termos)
        }
    }

    /** Adiciona o termo (após trim) se não vazio e ainda não presente. Retorna true se adicionou. */
    fun add(termo: String): Boolean = synchronized(trava) {
        val t = termo.trim()
        val atual = load()
        if (t.isEmpty() || atual.contains(t)) return@synchronized false
        save(atual + t)
        true
    }

    fun remove(termo: String) {
        synchronized(trava) {
            save(load().filterNot { it == termo })
        }
    }

    companion object {
        // Um lock só para a classe inteira: ReaderViewModel e SettingsActivity criam instâncias diferentes,
        // então um lock por instância não impediria que duas escritas se sobrescrevessem.
        private val trava = Any()

        fun parse(json: String): List<String> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        fun serialize(termos: List<String>): String = JSONArray(termos).toString()
    }
}
