package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Codificador de voz (Mimi encoder) do Pocket TTS: transforma o áudio de referência do usuário na
 * "impressão" da voz. Cada idioma tem o seu (os pesos diferem), baixado sob demanda só para quem usa a
 * clonagem naquele idioma, e copiado para `models/mimi_encoder.onnx` do pacote do idioma.
 */
object PocketEncoderManager {
    private const val TAG = "PocketEncoder"
    private const val BASE_URL =
        "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/pocket-tts-v3.3.0"
    /** Nome do arquivo DENTRO do pacote (é o que o motor nativo procura). */
    const val FILE_NAME = "mimi_encoder.onnx"
    const val DOWNLOAD_MB = 40
    private const val MIN_BYTES = 1024L * 1024L
    private const val MAX_BYTES = 120L * 1024L * 1024L

    private fun dir(context: Context) = File(context.filesDir, "pockettts")

    /** "pt-BR" → "pt", "en-US" → "en", "es-ES" → "es". */
    private fun codigo(languageTag: String) = languageTag.substringBefore('-').lowercase()

    private fun remoto(languageTag: String) = "mimi_encoder-${codigo(languageTag)}.onnx"

    fun installedFile(context: Context, languageTag: String) = File(dir(context), remoto(languageTag))

    fun isInstalled(context: Context, languageTag: String): Boolean =
        installedFile(context, languageTag).let { it.isFile && it.length() >= MIN_BYTES }

    /** Garante o codificador do idioma dentro de `models/` do pacote (cópia). Falso se ainda não foi baixado. */
    fun installInto(context: Context, languageTag: String, packRoot: File): Boolean {
        if (!isInstalled(context, languageTag)) return false
        val origem = installedFile(context, languageTag)
        val destino = File(File(packRoot, "models"), FILE_NAME)
        if (destino.isFile && destino.length() == origem.length()) return true
        return runCatching {
            destino.parentFile?.mkdirs()
            val parcial = File(destino.parentFile, "$FILE_NAME.part")
            origem.copyTo(parcial, overwrite = true)
            if (!parcial.renameTo(destino)) {
                parcial.copyTo(destino, overwrite = true)
                parcial.delete()
            }
            true
        }.getOrElse {
            Log.e(TAG, "Não foi possível copiar o codificador para o pacote: ${it.message}")
            false
        }
    }

    fun tamanhoOcupado(context: Context): Long =
        dir(context).listFiles { f -> f.name.startsWith("mimi_encoder-") && f.name.endsWith(".onnx") }
            ?.sumOf { it.length() } ?: 0L

    /** Remove os codificadores baixados (e as cópias dentro dos pacotes). As vozes clonadas continuam salvas. */
    fun delete(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("mimi_encoder-") }?.forEach { it.delete() }
        dir(context).listFiles { f -> f.isDirectory }?.forEach { File(File(it, "models"), FILE_NAME).delete() }
    }

    /** Remove só o codificador de um idioma (arquivo, parcial e a cópia dentro dos pacotes). */
    fun delete(context: Context, languageTag: String) {
        val alvo = installedFile(context, languageTag)
        alvo.delete()
        File(alvo.parentFile, "${alvo.name}.part").delete()
        dir(context).listFiles { f -> f.isDirectory }?.forEach { File(File(it, "models"), FILE_NAME).delete() }
    }

    /** Baixa e confere o checksum do codificador do idioma (download retomável, como os demais pacotes). */
    suspend fun download(context: Context, languageTag: String, onProgress: ProgressoDownload) =
        withContext(Dispatchers.IO) {
            if (isInstalled(context, languageTag)) {
                onProgress("Pronto", 1f, 1L, 1L)
                return@withContext
            }
            val url = "$BASE_URL/${remoto(languageTag)}"
            val alvo = installedFile(context, languageTag)
            val sidecar = File(dir(context), "${remoto(languageTag)}.sha256")
            dir(context).mkdirs()
            val total = SupertonicAssetManager.probeFileSize(url)
            if (total < MIN_BYTES || total > MAX_BYTES) {
                throw IOException("O codificador de voz ainda não está disponível para download.")
            }
            var baixado = File(dir(context), "${alvo.name}.part").let { if (it.exists()) it.length() else 0L }
            onProgress("Baixando codificador de voz", 0f, baixado, total)
            SupertonicAssetManager.downloadFileWithResume(url, alvo) { delta ->
                baixado += delta
                onProgress("Baixando codificador de voz", (baixado.toFloat() / total).coerceIn(0f, 1f), baixado, total)
            }
            SupertonicAssetManager.downloadFileWithResume("$url.sha256", sidecar) { }
            val esperado = sidecar.readText().trim().split(Regex("\\s+"), limit = 2).first()
            val atual = sha256(alvo)
            sidecar.delete()
            if (!esperado.matches(Regex("[0-9a-fA-F]{64}")) || !atual.equals(esperado, ignoreCase = true)) {
                alvo.delete()
                throw IOException("Checksum do codificador de voz não confere.")
            }
            onProgress("Pronto", 1f, total, total)
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
