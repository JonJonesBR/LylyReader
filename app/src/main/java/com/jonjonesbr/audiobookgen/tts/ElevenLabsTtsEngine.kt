package com.jonjonesbr.audiobookgen.tts

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Síntese via API oficial da ElevenLabs (https://elevenlabs.io/docs/api-reference), usando a
 * chave própria do usuário — sem passar pelo Python/Chaquopy (chamada HTTP simples, não precisa
 * de nenhum pacote pip novo).
 */
class ElevenLabsTtsEngine {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val RITMO_BASE = 100f
        // ElevenLabs só aceita "speed" no intervalo 0.7–1.2 em voice_settings.
        private const val SPEED_MIN = 0.7f
        private const val SPEED_MAX = 1.2f
        private const val MODEL_ID = "eleven_multilingual_v2"
        const val MIN_AUDIO_BYTES = 500L
    }

    /** Sintetiza [texto] com a voz [voiceId], escrevendo o MP3 retornado em [outFile]. */
    // A chamada de rede pode falhar de várias formas (IOException de conexão/leitura,
    // JSONException, IllegalStateException da validação da chave) — captura ampla
    // intencional pra sempre devolver Result.failure em vez de propagar.
    @Suppress("TooGenericExceptionCaught")
    fun synthesizeSync(texto: String, voiceId: String, apiKey: String, ritmo: Int, outFile: File): Result<Unit> =
        try {
            check(apiKey.isNotBlank()) { "chave elevenlabs ausente" }
            val conn = abrirConexao(voiceId, apiKey)
            enviarCorpo(conn, texto, ritmo)
            verificarResposta(conn)?.let { throw it }
            salvarAudio(conn, outFile)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun abrirConexao(voiceId: String, apiKey: String): HttpURLConnection {
        val url = URI("https://api.elevenlabs.io/v1/text-to-speech/$voiceId").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
        }
    }

    private fun enviarCorpo(conn: HttpURLConnection, texto: String, ritmo: Int) {
        val speed = (ritmo / RITMO_BASE).coerceIn(SPEED_MIN, SPEED_MAX)
        val body = JSONObject().apply {
            put("text", texto)
            put("model_id", MODEL_ID)
            put("voice_settings", JSONObject().put("speed", speed))
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun verificarResposta(conn: HttpURLConnection): IOException? {
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_OK) return null
        val erro = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        return IOException("elevenlabs http $code: $erro")
    }

    private fun salvarAudio(conn: HttpURLConnection, outFile: File) {
        outFile.parentFile?.mkdirs()
        conn.inputStream.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
        if (!outFile.exists() || outFile.length() < MIN_AUDIO_BYTES) {
            throw IOException("elevenlabs retornou audio vazio")
        }
    }
}
