package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** Download sob demanda e validação dos pacotes fixos públicos do Pocket TTS 3.3. */
object PocketTtsModelManager {
    private const val TAG = "PocketTtsModels"
    private const val BASE_URL =
        "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/pocket-tts-v3.3.0"
    private const val MAX_ARCHIVE_BYTES = 200L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 240L * 1024L * 1024L
    private const val MAX_ENTRIES = 16
    private const val BUFFER_SIZE = 64 * 1024
    private const val READY_MARKER = ".ready"

    data class Spec(
        val id: String,
        val languageTag: String,
        val voiceId: String,
        val voiceFile: String,
        val archive: String,
        val displayName: String,
        val tokenizerSha256: String,
        val tokenizerAsset: String
    )

    private val specs = listOf(
        Spec(
            id = "pocket-ptbr-int8",
            languageTag = "pt-BR",
            voiceId = "pocket-ptbr-rafael",
            voiceFile = "rafael.kv",
            archive = "pocket-ptbr-int8.zip",
            displayName = "Rafael",
            tokenizerSha256 = "fe9db388dc987c8a26bb88789d2f20989e68e00d8534442a141ecccec21cedba",
            tokenizerAsset = "pocket/tokenizers/pt-BR.model"
        ),
        Spec(
            id = "pocket-en-int8",
            languageTag = "en-US",
            voiceId = "pocket-en-alba",
            voiceFile = "alba.kv",
            archive = "pocket-en-int8.zip",
            displayName = "Alba",
            tokenizerSha256 = "d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6",
            tokenizerAsset = "pocket/tokenizers/en-US.model"
        ),
        Spec(
            id = "pocket-es-int8",
            languageTag = "es-ES",
            voiceId = "pocket-es-lola",
            voiceFile = "lola.kv",
            archive = "pocket-es-int8.zip",
            displayName = "Lola",
            tokenizerSha256 = "9714c8ce180d8147ab4c1406cd759619828f95d7000c87c41dc9ccec527a3e89",
            tokenizerAsset = "pocket/tokenizers/es-ES.model"
        )
    )

    private val requiredFiles = listOf(
        "manifest.json",
        "models/text_conditioner.onnx",
        "models/tokenizer.model",
        "models/flow_lm_main_int8.onnx",
        "models/flow_lm_flow_int8.onnx",
        "models/mimi_decoder_int8.onnx"
    )

    fun spec(packId: String): Spec? = specs.firstOrNull { it.id == packId }

    /**
     * Pacote de idioma da voz. Vozes clonadas ([PocketCustomVoices]) usam o pacote do idioma
     * embutido no id; o arquivo de voz delas NÃO é [Spec.voiceFile] (é o áudio de referência do usuário).
     */
    fun specForVoice(voiceId: String): Spec? {
        PocketCustomVoices.languageTagOf(voiceId)?.let { tag -> return specs.firstOrNull { it.languageTag == tag } }
        return specs.firstOrNull { it.voiceId == voiceId }
    }

    fun packIdForVoice(voiceId: String): String? = specForVoice(voiceId)?.id

    fun packDir(context: Context, packId: String): File? =
        if (spec(packId) == null) null else File(File(context.filesDir, "pockettts"), packId)

    fun isReady(context: Context, packId: String): Boolean {
        val model = spec(packId) ?: return false
        val root = packDir(context, model.id) ?: return false
        if (!File(root, READY_MARKER).isFile) return false
        if (requiredFiles.any { relative ->
                val file = File(root, relative)
                !file.isFile || file.length() < minimumSize(relative)
            }) return false
        if (!hasExpectedTokenizer(root, model) && !repairTokenizer(context, root, model)) return false
        val voice = File(root, "voices/${model.voiceFile}")
        if (!voice.isFile || voice.length() < MIN_VOICE_BYTES) return false
        return runCatching {
            val manifest = JSONObject(File(root, "manifest.json").readText())
            manifest.optInt("format") == 1 &&
                manifest.optString("id") == model.id &&
                manifest.optString("languageTag") == model.languageTag &&
                manifest.optString("voiceId") == model.voiceId &&
                manifest.optString("license") == "CC-BY-4.0"
        }.getOrDefault(false)
    }

    fun tamanhoOcupado(context: Context, packId: String): Long {
        val dir = packDir(context, packId) ?: return 0L
        return if (dir.isDirectory) dir.walkTopDown().filter(File::isFile).sumOf(File::length) else 0L
    }

    fun delete(context: Context, packId: String) {
        val model = spec(packId) ?: return
        packDir(context, model.id)?.deleteRecursively()
        File(context.filesDir, model.archive).delete()
        File(context.filesDir, "${model.archive}.sha256").delete()
        File(context.filesDir, "${model.archive}.part").delete()
        File(context.filesDir, "${model.archive}.sha256.part").delete()
    }

    /**
     * Downloads one language pack after an explicit user choice. The checksum is fetched from
     * a sidecar and checked before a bounded, path-safe extraction is atomically activated.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun download(context: Context, packId: String, onProgress: ProgressoDownload) =
        withContext(Dispatchers.IO) {
            val model = spec(packId) ?: throw IOException("Pacote Pocket desconhecido: $packId")
            if (isReady(context, model.id)) {
                onProgress("Pronto", 1f, 1L, 1L)
                return@withContext
            }

            val archive = File(context.filesDir, model.archive)
            val sidecar = File(context.filesDir, "${model.archive}.sha256")
            val staging = File(context.cacheDir, "${model.id}-staging")
            val destination = packDir(context, model.id) ?: throw IOException("Diretório de pacote inválido")
            try {
                staging.deleteRecursively()
                val url = "$BASE_URL/${model.archive}"
                val total = SupertonicAssetManager.probeFileSize(url)
                if (total <= 0L || total > MAX_ARCHIVE_BYTES) {
                    throw IOException("Tamanho do pacote Pocket inválido (${total / (1024 * 1024)} MB).")
                }
                var downloaded = archivePart(archive).length()
                var lastProgressAt = 0L
                onProgress("Baixando Pocket TTS · ${model.languageTag}", 0f, downloaded, total)
                SupertonicAssetManager.downloadFileWithResume(url, archive) { delta ->
                    downloaded += delta
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= 100L) {
                        onProgress(
                            "Baixando Pocket TTS · ${model.languageTag}",
                            (downloaded.toFloat() / total).coerceIn(0f, 1f), downloaded, total
                        )
                        lastProgressAt = now
                    }
                }
                if (archive.length() != total) throw IOException("Download Pocket incompleto.")

                SupertonicAssetManager.downloadFileWithResume("$url.sha256", sidecar) { }
                val expected = sidecar.readText().trim().split(Regex("\\s+"), limit = 2).first()
                if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) {
                    throw IOException("Checksum Pocket inválido.")
                }
                val actual = sha256(archive)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("Checksum do pacote Pocket não confere.")
                }

                extractPack(archive, staging, model)
                if (!hasExpectedTokenizer(staging, model) && !repairTokenizer(context, staging, model)) {
                    throw IOException("Não foi possível instalar o tokenizer correto do Pocket.")
                }
                if (!isReadyAt(staging, model)) throw IOException("Pacote Pocket incompleto após extração.")

                destination.parentFile?.mkdirs()
                val backup = File(destination.parentFile, ".backup-${model.id}")
                backup.deleteRecursively()
                if (destination.exists() && !destination.renameTo(backup)) {
                    throw IOException("Não foi possível atualizar o pacote Pocket existente.")
                }
                if (!staging.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    throw IOException("Não foi possível ativar o pacote Pocket.")
                }
                backup.deleteRecursively()
                if (!isReady(context, model.id)) throw IOException("Pacote Pocket falhou na verificação final.")
                archive.delete()
                sidecar.delete()
                onProgress("Pronto", 1f, total, total)
            } catch (error: CancellationException) {
                staging.deleteRecursively()
                throw error
            } catch (error: Exception) {
                staging.deleteRecursively()
                archive.delete()
                archivePart(archive).delete()
                sidecar.delete()
                archivePart(sidecar).delete()
                Log.e(TAG, "Falha ao baixar pacote ${model.id}: ${error.message}", error)
                throw error
            }
        }

    private fun isReadyAt(root: File, model: Spec): Boolean {
        if (requiredFiles.any { relative ->
                val file = File(root, relative)
                !file.isFile || file.length() < minimumSize(relative)
            }) return false
        if (!hasExpectedTokenizer(root, model)) return false
        val voice = File(root, "voices/${model.voiceFile}")
        if (!voice.isFile || voice.length() < MIN_VOICE_BYTES) return false
        return runCatching {
            val manifest = JSONObject(File(root, "manifest.json").readText())
            manifest.optInt("format") == 1 &&
                manifest.optString("id") == model.id &&
                manifest.optString("languageTag") == model.languageTag &&
                manifest.optString("voiceId") == model.voiceId &&
                manifest.optString("license") == "CC-BY-4.0"
        }.getOrDefault(false)
    }

    private fun extractPack(archive: File, staging: File, model: Spec) {
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Não foi possível preparar a extração Pocket.")
        val allowed = (requiredFiles + "voices/${model.voiceFile}").toSet()
        val canonicalRoot = staging.canonicalFile
        var entryCount = 0
        var extractedBytes = 0L
        val seen = mutableSetOf<String>()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw IOException("Pacote Pocket tem entradas demais.")
                val name = entry.name
                if (entry.isDirectory) {
                    val directory = name.removeSuffix("/")
                    if (!isSafeArchivePath(directory) || directory !in setOf("models", "voices")) {
                        throw IOException("Caminho inseguro no pacote Pocket.")
                    }
                    zip.closeEntry()
                    continue
                }
                if (!isSafeArchivePath(name) || name !in allowed || !seen.add(name)) {
                    throw IOException("Arquivo inesperado ou duplicado no pacote Pocket: $name")
                }
                val target = File(canonicalRoot, name).canonicalFile
                if (target.parentFile != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw IOException("Caminho fora do pacote Pocket.")
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        extractedBytes += count
                        if (extractedBytes > MAX_EXPANDED_BYTES) {
                            throw IOException("O pacote Pocket excedeu o limite de extração.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
        }
        if (seen != allowed) throw IOException("O pacote Pocket não contém todos os arquivos esperados.")
        File(staging, READY_MARKER).writeText("Pocket TTS 3.3 · ${model.id}\n")
    }

    internal fun isSafeArchivePath(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
            name.split('/').none { it.isBlank() || it == "." || it == ".." }

    internal fun hasExpectedTokenizer(root: File, model: Spec): Boolean {
        val tokenizer = File(root, "models/tokenizer.model")
        return tokenizer.isFile && sha256(tokenizer).equals(model.tokenizerSha256, ignoreCase = true)
    }

    private fun repairTokenizer(context: Context, root: File, model: Spec): Boolean {
        if (hasExpectedTokenizer(root, model)) return true
        return try {
            context.assets.open(model.tokenizerAsset).use { source ->
                replaceTokenizerFromTrustedSource(root, model, source)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not load bundled tokenizer for ${model.languageTag}.", error)
            false
        }
    }

    /** Atomically repairs an older pack only after the bundled tokenizer matches its pinned hash. */
    internal fun replaceTokenizerFromTrustedSource(root: File, model: Spec, source: InputStream): Boolean {
        val tokenizer = File(root, "models/tokenizer.model")
        val parent = tokenizer.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temporary = File(parent, ".tokenizer-${model.id}.part")
        return try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_TOKENIZER_BYTES) throw IOException("Tokenizer Pocket excedeu o limite.")
                    output.write(buffer, 0, count)
                }
            }
            if (!sha256(temporary).equals(model.tokenizerSha256, ignoreCase = true)) return false
            try {
                Files.move(
                    temporary.toPath(), tokenizer.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), tokenizer.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            hasExpectedTokenizer(root, model)
        } catch (error: Exception) {
            Log.e(TAG, "Could not repair tokenizer for ${model.languageTag}.", error)
            false
        } finally {
            temporary.delete()
        }
    }

    private fun minimumSize(name: String): Long = when {
        name == "manifest.json" -> MIN_MANIFEST_BYTES
        name.endsWith("tokenizer.model") -> MIN_TOKENIZER_BYTES
        else -> MIN_MODEL_BYTES
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun archivePart(target: File) = File(target.parentFile, "${target.name}.part")

    private const val MIN_MODEL_BYTES = 1024L * 1024L
    private const val MIN_MANIFEST_BYTES = 80L
    private const val MIN_TOKENIZER_BYTES = 1024L
    private const val MIN_VOICE_BYTES = 1024L
    private const val MAX_TOKENIZER_BYTES = 1024L * 1024L
}
