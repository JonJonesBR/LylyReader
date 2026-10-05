package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Análise de personagens por IA (opcional, só por ação explícita do usuário). A heurística local já divide
 * o livro em falas e narração; a IA só decide QUEM fala cada fala, e suas respostas viram correções
 * ([OfflineSpeakerAttributor.analyze] com `aiOverrides`). O texto enviado é só o dos parágrafos com fala
 * (mais 2 de contexto), em lotes, com a chave Gemini do próprio usuário.
 */
object SpeakerAiPlanner {
    const val MAX_CHARS_PER_BATCH = 7_000
    const val MAX_PARAGRAPHS_PER_BATCH = 30
    const val CONTEXT_PARAGRAPHS = 2

    /** Lote: [classify] são os parágrafos cujas falas serão classificadas; [context] só ajuda a entender. */
    data class Batch(val classify: List<Int>, val context: List<Int>)

    fun dialogueParagraphs(paragraphs: List<String>): List<Int> =
        paragraphs.indices.filter { index -> OfflineSpeakerAttributor.partsOf(paragraphs[index]).any { it.speech } }

    /** Agrupa os parágrafos com fala, em ordem, em lotes de tamanho limitado. */
    fun planBatches(paragraphs: List<String>, skip: Set<Int> = emptySet()): List<Batch> {
        val pending = dialogueParagraphs(paragraphs).filter { it !in skip }
        val batches = mutableListOf<Batch>()
        var current = mutableListOf<Int>()
        var chars = 0
        fun flush() {
            if (current.isEmpty()) return
            val first = current.first()
            val context = (first - CONTEXT_PARAGRAPHS until first).filter { it >= 0 && it !in current }
            batches += Batch(current.toList(), context)
            current = mutableListOf()
            chars = 0
        }
        pending.forEach { index ->
            val size = paragraphs[index].length
            if (current.isNotEmpty() && (chars + size > MAX_CHARS_PER_BATCH || current.size >= MAX_PARAGRAPHS_PER_BATCH)) flush()
            current += index
            chars += size
        }
        flush()
        return batches
    }

    /** Texto do parágrafo com cada fala marcada como ⟦n⟧…⟦/n⟧ (n = número da fala no parágrafo). */
    fun markedText(paragraph: String): String = OfflineSpeakerAttributor.partsOf(paragraph).joinToString("") { part ->
        if (part.speech) "⟦${part.ordinal}⟧${part.text}⟦/${part.ordinal}⟧" else part.text
    }

    fun buildPrompt(paragraphs: List<String>, batch: Batch, cast: List<String>): String {
        val conhecidos = if (cast.isEmpty()) "(ainda nenhum)" else cast.joinToString(", ")
        val corpo = StringBuilder()
        (batch.context + batch.classify).sorted().forEach { index ->
            val rotulo = if (index in batch.classify) "CLASSIFICAR" else "contexto"
            corpo.append('[').append(index).append("] (").append(rotulo).append(") ")
                .append(markedText(paragraphs[index]).trim()).append("\n\n")
        }
        return """
Você ajuda a montar um audiobook com vozes diferentes para cada personagem. Abaixo há parágrafos de um livro, numerados entre colchetes. Cada fala de personagem está marcada como ⟦n⟧texto da fala⟦/n⟧, onde n é o número da fala dentro do parágrafo.

Para TODAS as falas dos parágrafos marcados "CLASSIFICAR", diga quem fala. Os parágrafos "contexto" servem só para entender a cena.

Regras:
- Personagens já conhecidos: $conhecidos. Se for uma dessas pessoas, use o nome EXATAMENTE como está na lista.
- Se for uma pessoa nova, use o nome curto pelo qual o livro a chama (sem títulos como "Sr." ou "Dona").
- Use "NARRADOR" se a fala é do próprio narrador (livro em primeira pessoa), se é uma voz coletiva/indefinida ou se o texto não permite saber. Não invente.
- Deduza pelo contexto: verbos de fala, quem acabou de agir, alternância do diálogo, vocativos.

Responda SOMENTE com um JSON: uma lista de objetos {"p": <número do parágrafo>, "n": <número da fala>, "quem": "<nome>"}, um para cada fala a classificar, sem texto fora do JSON.

$corpo""".trimIndent()
    }

    /** Lê a resposta da IA (tolera cercas de código e texto ao redor da lista). */
    fun parseResponse(raw: String): Map<Pair<Int, Int>, String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val items = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, String>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val p = o.optInt("p", -1)
            val n = o.optInt("n", -1)
            val who = (o.optString("quem").ifBlank { o.optString("who") }).trim()
            if (p < 0 || n < 1 || who.isEmpty()) continue
            result[p to n] = normalizeName(who)
        }
        return result
    }

    /** "NARRADOR"/"narrator"/"desconhecido" viram a marca de narrador; o resto é o nome limpo. */
    fun normalizeName(name: String): String {
        val limpo = name.trim().trim('"', '.', ',')
        val chave = Normalizer.normalize(limpo, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return if (chave in NARRATOR_WORDS || chave.isEmpty()) OfflineSpeakerAttributor.AI_NARRATOR else limpo
    }

    private val NARRATOR_WORDS = setOf("narrador", "narradora", "narrator", "desconhecido", "unknown", "ninguem", "indefinido")

    /** Nomes (únicos, por id) das correções — alimentam a lista de "conhecidos" dos próximos lotes. */
    fun castFrom(labels: Map<Pair<Int, Int>, String>, seed: List<String> = emptyList()): List<String> {
        val vistos = linkedMapOf<String, String>()
        (seed + labels.values).forEach { name ->
            if (name != OfflineSpeakerAttributor.AI_NARRATOR && name.isNotBlank()) vistos.putIfAbsent(idOf(name), name)
        }
        return vistos.values.toList()
    }

    private fun idOf(name: String) =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

}

/** Cliente mínimo da API Gemini (texto → JSON), com rodízio de chaves e de modelos. */
class GeminiSpeakerClient(
    keys: List<String>,
    /** Modelo escolhido pelo usuário (tentado primeiro); vazio = só a lista automática. */
    preferredModel: String = "",
    private val pauseBetweenRequestsMs: Long = 1_200L
) : AiTextClient {
    override val rotulo: String get() = "Gemini"

    private val models: List<String> = (listOf(preferredModel.trim().removePrefix("models/")) + AUTO_MODELS)
        .filter { it.isNotEmpty() }.distinct()
    private val keys = keys.map { it.trim() }.filter { it.isNotEmpty() }
    /** Ordem em que os modelos são tentados (o escolhido pelo usuário primeiro). */
    val modelOrder: List<String> get() = models

    private var keyIndex = 0
    private var lastRequestAt = 0L

    class ApiException(message: String, val fatal: Boolean) : Exception(message)

    fun hasKeys() = keys.isNotEmpty()

    /** Envia [prompt] e devolve o texto da resposta. Repete com outra chave/modelo quando faz sentido. */
    override fun generate(prompt: String): String {
        if (keys.isEmpty()) throw ApiException("Nenhuma chave Gemini configurada.", true)
        var lastError: ApiException? = null
        for (model in models) {
            var attempts = 0
            while (attempts < MAX_ATTEMPTS_PER_MODEL) {
                waitTurn()
                val key = keys[keyIndex % keys.size]
                try {
                    return request(model, key, prompt)
                } catch (e: ApiException) {
                    lastError = e
                    if (e.fatal) throw e
                    keyIndex++
                    attempts++
                    Thread.sleep(BACKOFF_MS * attempts)
                } catch (e: java.io.IOException) {
                    lastError = ApiException("Falha de rede: ${e.message}", false)
                    attempts++
                    Thread.sleep(BACKOFF_MS * attempts)
                }
            }
        }
        throw lastError ?: ApiException("A IA não respondeu.", false)
    }

    private fun waitTurn() {
        val wait = lastRequestAt + pauseBetweenRequestsMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun request(model: String, key: String, prompt: String): String {
        val url = URI("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").toURL()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                code in 200..299 -> {
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val out = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) out.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    if (out.isEmpty()) throw ApiException("Resposta vazia da IA.", false)
                    return out.toString()
                }
                code == 400 || code == 401 || code == 403 ->
                    throw ApiException("A chave Gemini foi recusada (HTTP $code).", true)
                code == 404 -> throw ApiException("Modelo indisponível ($model).", false)
                else -> throw ApiException("A IA respondeu com erro (HTTP $code).", false)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Modo automático: primeiro os "Flash-Lite" (maior cota gratuita por dia), depois os Flash. Cai
         * para o seguinte quando o modelo não existe mais para a conta (HTTP 404). Conferido na
         * documentação do Gemini API em 2026-09-30 (o 2.0 foi desligado e o 2.5 só para quem já usava).
         */
        val AUTO_MODELS = listOf(
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.8-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash"
        )
        private const val MAX_ATTEMPTS_PER_MODEL = 3
        private const val BACKOFF_MS = 4_000L

        private val EXCLUDED_WORDS = listOf(
            "tts", "image", "embedding", "live", "audio", "robotics", "computer-use", "imagen", "veo", "aqa", "learnlm", "gemma", "vision"
        )

        /**
         * Pergunta à API quais modelos a chave acessa para gerar texto. Devolve os nomes curtos
         * (sem "models/"), "lite" primeiro, depois "flash", "pro" por último.
         */
        fun listModels(key: String): List<String> {
            val url = URI("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").toURL()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("x-goog-api-key", key.trim())
            }
            try {
                if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val items = json.optJSONArray("models") ?: return emptyList()
                val found = mutableListOf<String>()
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    val name = o.optString("name").removePrefix("models/")
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    val generates = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    if (generates && name.startsWith("gemini") && EXCLUDED_WORDS.none { name.contains(it) }) found += name
                }
                return sortModels(found)
            } finally {
                connection.disconnect()
            }
        }

        /** Ordem de exibição: Flash-Lite, Flash, Pro; dentro de cada grupo os nomes mais novos antes. */
        fun sortModels(names: List<String>): List<String> = names.distinct().sortedWith(
            compareBy<String> {
                when {
                    it.contains("lite") -> 0
                    it.contains("flash") -> 1
                    else -> 2
                }
            }.thenByDescending { it }
        )

        /** Chaves separadas por quebra de linha, vírgula ou ponto e vírgula. */
        fun parseKeys(raw: String): List<String> = raw.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
    }
}

/** Correções da IA por livro, salvas em disco (permite retomar e reaplicar depois de reanalisar). */
class SpeakerAiStore(private val directory: File) {
    data class State(val labels: Map<Pair<Int, Int>, String>, val doneParagraphs: Set<Int>)

    fun load(bookIdentity: String, paragraphs: List<String>): State {
        val file = fileFor(bookIdentity)
        if (bookIdentity.isBlank() || !file.isFile) return State(emptyMap(), emptySet())
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optString("content") != fingerprint(paragraphs)) return State(emptyMap(), emptySet())
            val labels = mutableMapOf<Pair<Int, Int>, String>()
            val obj = json.getJSONObject("labels")
            obj.keys().forEach { key ->
                val (p, n) = key.split(':').map(String::toInt)
                labels[p to n] = obj.getString(key)
            }
            val done = json.getJSONArray("done")
            State(labels, (0 until done.length()).map { done.getInt(it) }.toSet())
        }.getOrDefault(State(emptyMap(), emptySet()))
    }

    fun save(bookIdentity: String, paragraphs: List<String>, state: State): Boolean {
        if (bookIdentity.isBlank()) return false
        return runCatching {
            directory.mkdirs()
            val json = JSONObject()
                .put("content", fingerprint(paragraphs))
                .put("labels", JSONObject().apply { state.labels.forEach { (k, v) -> put("${k.first}:${k.second}", v) } })
                .put("done", JSONArray(state.doneParagraphs.sorted()))
            val target = fileFor(bookIdentity)
            val temp = File(directory, target.name + ".tmp")
            FileOutputStream(temp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            true
        }.getOrDefault(false)
    }

    fun clear(bookIdentity: String) {
        fileFor(bookIdentity).delete()
    }

    /** Migração de caminho: leva as correções da IA para a identidade nova, se ela ainda não tiver as suas. */
    fun migrarIdentidade(antiga: String, nova: String): Boolean {
        if (antiga.isBlank() || nova.isBlank() || antiga == nova) return false
        val origem = fileFor(antiga)
        val destino = fileFor(nova)
        if (!origem.isFile || destino.exists()) return false
        return origem.copyTo(destino, overwrite = false).isFile
    }

    private fun fileFor(bookIdentity: String) = File(directory, "ai_" + sha(bookIdentity).take(24) + ".json")

    private fun fingerprint(paragraphs: List<String>) = sha(paragraphs.joinToString("\u0000"))

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Percorre os lotes, chama a IA e junta as correções; o andamento é salvo a cada lote (dá para retomar). */
class SpeakerAiRunner(private val client: AiTextClient) {
    data class Progress(val batchesDone: Int, val batchesTotal: Int)

    suspend fun run(
        paragraphs: List<String>,
        initial: SpeakerAiStore.State,
        localCast: List<String>,
        onProgress: (Progress) -> Unit,
        onSave: (SpeakerAiStore.State) -> Unit
    ): SpeakerAiStore.State {
        val labels = initial.labels.toMutableMap()
        val done = initial.doneParagraphs.toMutableSet()
        val batches = SpeakerAiPlanner.planBatches(paragraphs, done)
        onProgress(Progress(0, batches.size))
        batches.forEachIndexed { position, batch ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val prompt = SpeakerAiPlanner.buildPrompt(paragraphs, batch, SpeakerAiPlanner.castFrom(labels, localCast))
            val answer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.generate(prompt) }
            val accepted = SpeakerAiPlanner.parseResponse(answer).filter { (key, _) ->
                key.first in batch.classify &&
                    key.second <= OfflineSpeakerAttributor.partsOf(paragraphs[key.first]).count { it.speech }
            }
            labels.putAll(accepted)
            done.addAll(batch.classify)
            onSave(SpeakerAiStore.State(labels.toMap(), done.toSet()))
            onProgress(Progress(position + 1, batches.size))
        }
        return SpeakerAiStore.State(labels.toMap(), done.toSet())
    }
}
