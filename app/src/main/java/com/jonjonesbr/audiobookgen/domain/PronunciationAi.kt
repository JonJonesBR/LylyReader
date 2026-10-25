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
    // Lotes menores: com 40 nomes a resposta ficava longa e às vezes chegava cortada (JSON inválido).
    const val BATCH_SIZE = 25
    private const val MIN_MID_SENTENCE_OCCURRENCES = 2

    data class Candidate(val word: String, val count: Int)

    private val NAME = Regex("\\p{Lu}[\\p{L}'’-]{2,}")
    private val WORD = Regex("\\p{L}[\\p{L}'’-]*")
    private val MARKS = Regex("\\p{M}+")
    private val SENTENCE_END = setOf('.', '!', '?', '…', ':')
    private val OPENERS = setOf('"', '“', '«', '(', '[', '—', '–', '-', '\'', '‘', '*', '_')

    /**
     * Nomes próprios prováveis, do mais frequente ao menos: palavra capitalizada que aparece pelo menos 2 vezes
     * no meio de frases e nunca em minúscula no livro (descarta "Terra", "Escola", "Você"). Palavras todas em
     * maiúsculas ("SURGE", "EUE") são siglas ou gritos, que o normalizador já trata: ficam de fora. Já exclui o
     * que está no dicionário do usuário.
     */
    fun candidates(paragraphs: List<String>, dictionary: PronunciationDictionary, max: Int = MAX_CANDIDATES): List<Candidate> {
        val midSentence = HashMap<String, Int>()
        val shown = HashMap<String, String>()
        val lowercaseForms = HashSet<String>()
        for (paragraph in paragraphs) {
            for (m in WORD.findAll(paragraph)) {
                val w = m.value
                if (w[0].isLowerCase()) lowercaseForms += key(w)
            }
            for (m in NAME.findAll(paragraph)) {
                if (isAllCaps(m.value) || isSentenceStart(paragraph, m.range.first)) continue
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

    private fun isAllCaps(word: String) = word.none { it.isLowerCase() }

    private fun isSentenceStart(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in OPENERS)) i--
        return i < 0 || text[i] in SENTENCE_END
    }

    private fun key(word: String) =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    fun batches(names: List<String>): List<List<String>> = names.chunked(BATCH_SIZE)

    /**
     * Prompt para um lote de nomes. [languageCode] é o idioma do livro (e da voz que vai lê-lo): "pt", "en" ou "es".
     * A regra central é uma só: manter o som ORIGINAL do nome e só adaptar a escrita às regras de leitura da voz
     * (Travis → Trévis, não "Travís"). O português tem regras e exemplos próprios, que é onde a voz mais erra.
     */
    fun buildPrompt(names: List<String>, languageCode: String): String {
        val header = when (languageCode) {
            "en" -> PROMPT_EN
            "es" -> PROMPT_ES
            else -> PROMPT_PT
        }
        return header + "\n\n" + PROMPT_FORMAT + "\n\nNomes:\n" + names.joinToString("\n")
    }

    private val PROMPT_PT = """
Você prepara um livro em português para ser lido em voz alta por um sintetizador de voz (TTS) brasileiro. O TTS lê toda palavra com as regras de leitura do português do Brasil, por isso erra nomes estrangeiros: lê "Travis" como "Tra-VÍS" em vez de "TRÉ-vis".

Para cada nome da lista, escreva como um brasileiro escreveria o SOM desse nome falado do jeito natural no idioma de origem (em geral, inglês), para que o TTS, lendo em português, fale o nome como ele é falado de verdade.

Regras:
1. Mantenha a pronúncia original; só adapte a escrita. Não traduza nem aportuguese o nome (William soa "Uíliam", nunca "Guilherme").
2. Marque a sílaba tônica com acento (agudo ou circunflexo) sempre que o TTS puder errar a tônica.
3. Sons do inglês: "a" de Jake/Kate → "ei"; "i" de Mike/Riker → "ai"; "ee"/"ea"/"ie" → "i"; "oo" → "u"; "y" final → "i"; "j" e "g" suave → "dj"; "h" aspirado no início → "r" (o "r" inicial do português tem esse som); "w" → "u"; "th" → "t" ou "d"; "a" de Travis/Jack → "é".
4. Não acrescente vogal no fim de nome terminado em consoante e não troque "n" final por "m".
5. Se um leitor brasileiro já fala o nome certo (Pedro, Ana, Victor, Laura), devolva o nome igual.
6. Se não for nome (sigla, palavra comum, palavra inventada sem pronúncia conhecida), devolva igual.
7. Só letras (acentuadas ou não) e espaços: sem IPA, hífens, aspas ou explicações. Inicial maiúscula.

Exemplos: Travis → Trévis; Homer → Rômer; Riker → Ráiker; Mike → Máik; Jake → Djêik; Steve → Stív; George → Djórdj; Harry → Rári; Hermione → Ermáioni; William → Uíliam; Marvin → Márvin; Eridani → Erídani; Pedro → Pedro.""".trimIndent()

    private val PROMPT_ES = """
Preparas un libro en español para que lo lea en voz alta un sintetizador de voz (TTS) en español. El TTS lee toda palabra con las reglas de lectura del español, por eso se equivoca con los nombres extranjeros.

Para cada nombre de la lista, escribe con la ortografía del español el SONIDO de ese nombre tal como se pronuncia de forma natural en su idioma de origen (en general, inglés), para que el TTS lo diga como se dice de verdad. Mantén la pronunciación original, solo adapta la escritura; no traduzcas el nombre. Marca la sílaba tónica con tilde cuando ayude. Si el nombre ya se lee bien en español, o no es un nombre, devuélvelo igual. Solo letras y espacios: sin IPA, guiones ni explicaciones. Inicial mayúscula.

Ejemplos: Travis → Trávis; Mike → Máik; George → Yórch; Steve → Stív; Harry → Jári; Pedro → Pedro.""".trimIndent()

    private val PROMPT_EN = """
You are preparing an English book to be read aloud by an English text-to-speech voice (TTS). The TTS reads every word with English spelling rules, so it mispronounces invented or foreign names (fantasy, science fiction).

For each name in the list, respell its SOUND using simple English spelling, keeping the name's natural pronunciation (the one used in the story or in its language of origin), so the TTS says it correctly. If the TTS already reads the name correctly (John, Mary, London), or it is not a name, return it unchanged. Letters and spaces only: no IPA, hyphens or explanations. Keep the initial capital.

Examples: Siobhan → Shivawn; Saoirse → Seersha; Kvothe → Kvoth; John → John.""".trimIndent()

    private val PROMPT_FORMAT = """
Responda SOMENTE com um JSON: uma lista de objetos {"nome": "...", "falado": "..."}, um para cada nome, na mesma ordem, sem texto fora do JSON.""".trimIndent()

    private val PAIR = Regex(
        "\"(?:nome|name|word|palavra)\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*" +
            "\"(?:falado|spoken|fala|pronuncia)\"\\s*:\\s*\"([^\"]+)\""
    )

    /**
     * Lê a resposta da IA (tolera cercas de código, texto ao redor, objeto {nome: falado} e JSON cortado no meio,
     * do qual aproveita os pares completos).
     */
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
        // JSON cortado (resposta longa interrompida): aproveita os pares que chegaram inteiros.
        PAIR.findAll(raw).forEach { m -> result[m.groupValues[1].trim()] = m.groupValues[2].trim() }
        val objStart = raw.indexOf('{')
        val objEnd = raw.lastIndexOf('}')
        if (result.isEmpty() && objStart >= 0 && objEnd > objStart) {
            val obj = runCatching { JSONObject(raw.substring(objStart, objEnd + 1)) }.getOrNull()
            obj?.keys()?.forEach { name ->
                val spoken = obj.optString(name).trim()
                if (name.isNotBlank() && spoken.isNotEmpty()) result[name.trim()] = spoken
            }
        }
        return result
    }

    /** Sugestão da IA para [name] na resposta já lida (ignora maiúsculas e acentos que a IA tenha mudado). */
    fun spokenFor(name: String, byName: Map<String, String>): String? =
        byName[name] ?: byName.entries.firstOrNull { key(it.key) == key(name) }?.value

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
        val body = text.trimStart('\uFEFF').trim()
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
