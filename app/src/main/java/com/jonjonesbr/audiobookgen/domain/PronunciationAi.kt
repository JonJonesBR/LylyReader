package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Sugestão de pronúncias por IA: a heurística local escolhe os nomes próprios do livro que os motores
 * costumam errar (nomes inventados ou estrangeiros) e a IA devolve, para cada um, uma grafia fonética
 * que o motor lê melhor. O resultado só entra no dicionário depois que o usuário revisa.
 */
object PronunciationAiPlanner {
    const val MAX_CANDIDATES = 150
    const val BATCH_SIZE = 40
    private const val MIN_MID_SENTENCE_OCCURRENCES = 2

    data class Candidate(val word: String, val count: Int)

    private val NAME = Regex("\\p{Lu}[\\p{L}'’-]{2,}")
    private val SENTENCE_END = setOf('.', '!', '?', '…', ':')
    private val OPENERS = setOf('"', '“', '«', '(', '[', '—', '–', '-', '\'', '‘', '*', '_')

    /**
     * Nomes próprios prováveis, do mais frequente ao menos: palavra capitalizada que aparece pelo menos 2 vezes
     * no meio de frases e nunca em minúscula no livro (descarta "Terra", "Escola", "Você"). Já exclui o que está
     * no dicionário do usuário.
     */
    fun candidates(paragraphs: List<String>, dictionary: PronunciationDictionary, max: Int = MAX_CANDIDATES): List<Candidate> {
        val midSentence = HashMap<String, Int>()
        val shown = HashMap<String, String>()
        val lowercaseForms = HashSet<String>()
        for (paragraph in paragraphs) {
            for (m in Regex("\\p{L}[\\p{L}'’-]*").findAll(paragraph)) {
                val w = m.value
                if (w[0].isLowerCase()) lowercaseForms += key(w)
            }
            for (m in NAME.findAll(paragraph)) {
                if (isSentenceStart(paragraph, m.range.first)) continue
                val k = key(m.value)
                midSentence[k] = (midSentence[k] ?: 0) + 1
                shown.putIfAbsent(k, m.value)
            }
        }
        return midSentence.entries
            .filter { (k, n) -> n >= MIN_MID_SENTENCE_OCCURRENCES && k !in lowercaseForms }
            .map { (k, n) -> Candidate(shown.getValue(k), n) }
            .filter { dictionary.find(it.word) == null }
            .sortedWith(compareByDescending<Candidate> { it.count }.thenBy { it.word })
            .take(max)
    }

    private fun isSentenceStart(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in OPENERS)) i--
        return i < 0 || text[i] in SENTENCE_END
    }

    private fun key(word: String) =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun batches(names: List<String>): List<List<String>> = names.chunked(BATCH_SIZE)

    fun buildPrompt(names: List<String>, language: String): String = """
Você prepara um livro para ser lido em voz alta por um sintetizador de voz (TTS) em $language, que costuma errar nomes estrangeiros ou inventados (de ficção, fantasia, ficção científica).

Para cada nome abaixo, escreva uma grafia FONÉTICA usando apenas as regras de leitura de $language, de modo que o TTS fale o nome do jeito mais natural: como um leitor nativo leria, ou como o nome é pronunciado no idioma de origem quando isso for conhecido. Use sílabas simples, acentue a sílaba tônica quando ajudar, não use símbolos IPA, hífens nem maiúsculas no meio. Mantenha a inicial maiúscula.
Se o nome já é lido corretamente por um leitor nativo (ex.: Pedro, Maria), devolva-o igual.

Responda SOMENTE com um JSON: uma lista de objetos {"nome": "...", "falado": "..."}, um para cada nome, sem texto fora do JSON.

Nomes:
${names.joinToString("\n")}""".trimIndent()

    /** Lê a resposta da IA (tolera cercas de código, texto ao redor e objeto {nome: falado}). */
    fun parseResponse(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val arrayStart = raw.indexOf('[')
        val arrayEnd = raw.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val items = runCatching { JSONArray(raw.substring(arrayStart, arrayEnd + 1)) }.getOrNull()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val name = firstNonBlank(o, "nome", "name", "word", "palavra")
                    val spoken = firstNonBlank(o, "falado", "spoken", "fala", "pronuncia")
                    if (name.isNotEmpty() && spoken.isNotEmpty()) result[name] = spoken
                }
                if (result.isNotEmpty()) return result
            }
        }
        val objStart = raw.indexOf('{')
        val objEnd = raw.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            val obj = runCatching { JSONObject(raw.substring(objStart, objEnd + 1)) }.getOrNull()
            obj?.keys()?.forEach { name ->
                val spoken = obj.optString(name).trim()
                if (name.isNotBlank() && spoken.isNotEmpty()) result[name.trim()] = spoken
            }
        }
        return result
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }.orEmpty()

    /** Só vale como sugestão se a IA mudou algo e não inventou texto longo ou com símbolos. */
    fun isUsable(name: String, spoken: String): Boolean {
        if (spoken.equals(name, ignoreCase = true)) return false
        if (spoken.length > name.length * 3 + 6) return false
        return spoken.all { it.isLetter() || it == ' ' || it == '\'' || it == '’' }
    }
}

/** Importação e exportação de dicionários de pronúncia em formatos simples. */
object PronunciationTransfer {
    /**
     * Aceita JSON (nossa lista [{"word","spoken"}], ou um objeto {"palavra": "falada"}) e texto em linhas:
     * `palavra=falada`, `palavra;falada`, `palavra,falada`, `palavra → falada`, `palavra<TAB>falada`.
     * Linhas vazias e comentários (#, //) são ignorados.
     */
    fun parse(text: String): List<PronunciationEntry> {
        val body = text.trimStart('﻿').trim()
        if (body.isEmpty()) return emptyList()
        if (body.startsWith("[") || body.startsWith("{")) parseJson(body)?.let { return it }
        return body.lineSequence().mapNotNull(::parseLine).toList()
    }

    private fun parseJson(body: String): List<PronunciationEntry>? = runCatching {
        if (body.startsWith("[")) {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val word = o.optString("word").ifBlank { o.optString("palavra") }.ifBlank { o.optString("nome") }
                val spoken = o.optString("spoken").ifBlank { o.optString("falado") }
                PronunciationEntry(word, spoken).takeIf { word.isNotBlank() && spoken.isNotBlank() }
            }
        } else {
            val obj = JSONObject(body)
            obj.keys().asSequence().mapNotNull { word ->
                PronunciationEntry(word, obj.optString(word)).takeIf { word.isNotBlank() && it.spoken.isNotBlank() }
            }.toList()
        }
    }.getOrNull()

    private val SEPARATORS = listOf("=>", "→", "->", "=", ";", "\t", "|", ":", ",")

    private fun parseLine(raw: String): PronunciationEntry? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return null
        for (separator in SEPARATORS) {
            val at = line.indexOf(separator)
            if (at <= 0) continue
            val word = line.substring(0, at).trim().trim('"')
            val spoken = line.substring(at + separator.length).trim().trim('"')
            if (word.isNotEmpty() && spoken.isNotEmpty()) return PronunciationEntry(word, spoken)
        }
        return null
    }

    /** Junta [imported] ao dicionário atual; em palavras repetidas vale a importada. */
    fun merge(current: PronunciationDictionary, imported: List<PronunciationEntry>): PronunciationDictionary =
        PronunciationDictionary(current.entries + imported)

    fun export(dictionary: PronunciationDictionary): String =
        JSONArray().apply {
            dictionary.entries.sortedBy { it.word.lowercase() }
                .forEach { put(JSONObject().put("word", it.word).put("spoken", it.spoken)) }
        }.toString(2)
}
