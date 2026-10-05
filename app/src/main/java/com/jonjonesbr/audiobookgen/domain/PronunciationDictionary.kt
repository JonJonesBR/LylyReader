package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject

/** Uma palavra (ou expressão) do livro e como ela deve ser FALADA (grafia fonética livre). */
data class PronunciationEntry(val word: String, val spoken: String)

/**
 * Dicionário de pronúncia do usuário: antes da síntese, cada [PronunciationEntry.word] do texto é
 * trocada pela grafia em [PronunciationEntry.spoken]. Vale para qualquer motor porque age no texto,
 * não no modelo — a "pronúncia" é uma re-grafia (ex.: "Hermione" → "Hermáione"), não IPA.
 *
 * Regras: palavra inteira (não casa no meio de outra), sem diferenciar maiúsculas/minúsculas,
 * espaços internos flexíveis, expressão mais longa vence, troca em uma única passada (o resultado
 * de uma troca nunca é trocado de novo). Função pura, sem Android além do org.json.
 */
class PronunciationDictionary(entries: List<PronunciationEntry>) {
    val entries: List<PronunciationEntry> = entries
        .map { PronunciationEntry(collapse(it.word), it.spoken.trim()) }
        .filter { it.word.isNotEmpty() && it.spoken.isNotEmpty() }
        .associateBy { it.word.lowercase() }   // a última definição da mesma palavra vence
        .values.toList()

    private val spokenByWord: Map<String, String> = this.entries.associate { it.word.lowercase() to it.spoken }

    private val pattern: Regex? = if (this.entries.isEmpty()) null else {
        val alternation = this.entries
            .sortedByDescending { it.word.length }
            .joinToString("|") { entry -> entry.word.split(' ').joinToString("\\s+") { Regex.escape(it) } }
        Regex("(?<![\\p{L}\\p{N}_])(?:$alternation)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun apply(text: String): String {
        val regex = pattern ?: return text
        return regex.replace(text) { match -> spokenByWord[collapse(match.value).lowercase()] ?: match.value }
    }

    /** Adiciona ou substitui (mesma palavra, sem diferenciar caixa) uma entrada. */
    fun with(entry: PronunciationEntry): PronunciationDictionary = PronunciationDictionary(entries + entry)

    fun without(word: String): PronunciationDictionary {
        val key = collapse(word).lowercase()
        return PronunciationDictionary(entries.filterNot { it.word.lowercase() == key })
    }

    fun find(word: String): PronunciationEntry? = entries.firstOrNull { it.word.lowercase() == collapse(word).lowercase() }

    fun toJson(): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("word", it.word).put("spoken", it.spoken)) }
    }.toString()

    companion object {
        val EMPTY = PronunciationDictionary(emptyList())

        fun fromJson(json: String?): PronunciationDictionary {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val array = JSONArray(json)
                PronunciationDictionary(List(array.length()) { index ->
                    array.getJSONObject(index).let { PronunciationEntry(it.optString("word"), it.optString("spoken")) }
                })
            }.getOrDefault(EMPTY)
        }

        private fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    }
}
