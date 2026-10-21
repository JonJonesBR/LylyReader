package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Cliente de IA de texto: recebe um prompt e devolve a resposta (JSON em texto). Bloqueante. */
interface AiTextClient {
    /** Nome curto do serviço, para mensagens ao usuário. */
    val rotulo: String

    fun generate(prompt: String): String
}

class AiApiException(message: String, val fatal: Boolean) : Exception(message)

/** Qual IA o usuário escolheu para as análises (personagens, pronúncias). */
enum class AiProvider(val id: String) {
    GEMINI("gemini"),
    POLLINATIONS("pollinations"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/**
 * Cliente do formato "chat completions" da OpenAI, usado pelo Pollinations (grátis, sem chave) e por qualquer
 * serviço compatível que o usuário configure (OpenRouter, Groq, etc.). Repete com espera quando o serviço
 * responde com limite de uso ou erro temporário — o Pollinations anônimo faz isso com frequência.
 */
class OpenAiStyleClient(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String = "",
    override val rotulo: String,
    private val pauseBetweenRequestsMs: Long = 1_500L,
    private val context: Context
) : AiTextClient {
    private var lastRequestAt = 0L

    override fun generate(prompt: String): String {
        var lastError: AiApiException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val wait = lastRequestAt + pauseBetweenRequestsMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
            try {
                return request(prompt)
            } catch (e: AiApiException) {
                if (e.fatal) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = AiApiException(context.getString(R.string.ai_falha_rede, context.getString(mensagemDeErro(e))), false)
                    .also { it.initCause(e) }
            }
            if (attempt < MAX_ATTEMPTS) Thread.sleep(BACKOFF_MS * attempt)
        }
        throw lastError ?: AiApiException(context.getString(R.string.ai_nao_respondeu), false)
    }

    private fun request(prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 150_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return when {
                code in 200..299 -> extractContent(text, context)
                code == 401 || code == 403 ->
                    throw AiApiException(context.getString(R.string.ai_recusou_acesso, rotulo, code), true)
                code == 404 ->
                    throw AiApiException(context.getString(R.string.ai_modelo_nao_encontrado, rotulo), true)
                code == 402 || code == 429 ->
                    throw AiApiException(context.getString(R.string.ai_limite_uso, rotulo, code), false)
                else -> throw AiApiException(context.getString(R.string.ai_erro_resposta, rotulo, code), false)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_MS = 5_000L

        const val POLLINATIONS_ENDPOINT = "https://text.pollinations.ai/openai"
        const val POLLINATIONS_MODEL = "openai"

        /** Texto da resposta no formato OpenAI; vazio ou "{}" sem conteúdo conta como falha temporária. */
        fun extractContent(json: String, context: Context): String {
            val content = runCatching {
                JSONObject(json).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            }.getOrNull().orEmpty().trim()
            if (content.isEmpty() || content == "{}") throw AiApiException(context.getString(R.string.ai_resposta_vazia), false)
            return content
        }

        /** "https://openrouter.ai/api/v1" → ".../chat/completions"; endereço completo é mantido. */
        fun endpointFromBase(base: String): String {
            val limpo = base.trim().trimEnd('/')
            return if (limpo.endsWith("/chat/completions")) limpo else "$limpo/chat/completions"
        }
    }
}
