package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.jonjonesbr.audiobookgen.util.PacoteVozes
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Pacotes Piper/VITS publicados no catálogo oficial de modelos do sherpa-onnx. */
object PiperVitsModelManager {
    private const val TAG = "PiperVitsModelManager"
    private const val RELEASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val MB = 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 250L * MB
    private const val INTERVALO_PROGRESSO_MS = 100L
    private const val ESPEAK_MIN_FILES = 10

    data class Model(
        val id: String,
        val name: String,
        val quality: String,
        val archive: String,
        val onnx: String,
        /** Informação da licença dos dados de treinamento, não uma declaração sobre os pesos. */
        val licenseInfo: String,
        val attribution: String,
        val language: String = "pt-BR",
        val datasetName: String? = null,
        val expectedArchiveBytes: Long? = null,
        val noticeResource: Int? = null,
    ) {
        /** Tamanho decimal arredondado para cima; o progresso do download mostra os bytes reais. */
        val sizeMb: Int = expectedArchiveBytes?.let { ((it + 999_999L) / 1_000_000L).toInt() } ?: 90
    }

    val models = listOf(
        Model("piper-ptbr-cadu", "Cadu", "média", "vits-piper-pt_BR-cadu-medium", "pt_BR-cadu-medium.onnx", "MIT / CC0", "Piper Voices Cadu; dataset CC0. https://huggingface.co/rhasspy/piper-voices"),
        Model("piper-ptbr-dii", "Dii", "alta", "vits-piper-pt_BR-dii-high", "pt_BR-dii-high.onnx", "CC BY-NC-SA 4.0", "Dii PT-BR · CC BY-NC-SA 4.0. https://huggingface.co/csukuangfj/vits-piper-pt_BR-dii-high"),
        Model("piper-ptbr-edresson", "Edresson", "baixa", "vits-piper-pt_BR-edresson-low", "pt_BR-edresson-low.onnx", "MIT / CC BY 4.0", "Piper Voices Edresson; corpus CC BY 4.0. https://huggingface.co/csukuangfj/vits-piper-pt_BR-edresson-low"),
        Model("piper-ptbr-faber", "Faber", "média", "vits-piper-pt_BR-faber-medium", "pt_BR-faber-medium.onnx", "MIT / CC0", "Piper Voices Faber; dataset CC0. https://huggingface.co/rhasspy/piper-voices"),
        Model("piper-ptbr-jeff", "Jeff", "média", "vits-piper-pt_BR-jeff-medium", "pt_BR-jeff-medium.onnx", "MIT / CC0", "Piper Voices Jeff; dataset CC0. https://huggingface.co/rhasspy/piper-voices"),
        Model("piper-ptbr-miro", "Miro", "alta", "vits-piper-pt_BR-miro-high", "pt_BR-miro-high.onnx", "CC BY-NC-SA 4.0", "Miro PT-BR · CC BY-NC-SA 4.0. https://huggingface.co/TarcisoAmorim/piper-pt_BR-miro-high"),
        Model(
            id = "piper-enus-ljspeech-medium",
            name = "LJSpeech",
            quality = "medium",
            archive = "vits-piper-en_US-ljspeech-medium",
            onnx = "en_US-ljspeech-medium.onnx",
            licenseInfo = "Model repository MIT · public-domain dataset (LJSpeech)",
            attribution = "Piper en_US-ljspeech-medium; the model repository is MIT-licensed and its card lists LJSpeech as public domain. https://huggingface.co/rhasspy/piper-voices/blob/v1.0.0/en/en_US/ljspeech/medium/MODEL_CARD",
            language = "en-US",
            datasetName = "LJSpeech",
            expectedArchiveBytes = 67_169_893L,
            noticeResource = com.jonjonesbr.audiobookgen.R.string.piper_license_notice_public_domain,
        ),
        Model(
            id = "piper-enus-norman-medium",
            name = "Norman",
            quality = "medium",
            archive = "vits-piper-en_US-norman-medium",
            onnx = "en_US-norman-medium.onnx",
            licenseInfo = "Model repository MIT · public-domain dataset (LibriVox)",
            attribution = "Piper en_US-norman-medium; the model repository is MIT-licensed and its card lists LibriVox recordings as public domain. https://huggingface.co/rhasspy/piper-voices/blob/v1.0.0/en/en_US/norman/medium/MODEL_CARD",
            language = "en-US",
            datasetName = "LibriVox",
            expectedArchiveBytes = 67_203_672L,
            noticeResource = com.jonjonesbr.audiobookgen.R.string.piper_license_notice_public_domain,
        ),
        Model(
            id = "piper-esmx-claude-high",
            name = "Claude",
            quality = "high",
            archive = "vits-piper-es_MX-claude-high",
            onnx = "es_MX-claude-high.onnx",
            licenseInfo = "Model repository MIT · dataset Apache-2.0",
            attribution = "Piper es_MX-claude-high; the model repository is MIT-licensed and its card lists the Piper-TTS-Spanish dataset under Apache-2.0. https://huggingface.co/rhasspy/piper-voices/blob/v1.0.0/es/es_MX/claude/high/MODEL_CARD",
            language = "es-MX",
            datasetName = "Piper-TTS-Spanish",
            expectedArchiveBytes = 67_207_890L,
            noticeResource = com.jonjonesbr.audiobookgen.R.string.piper_license_notice_apache,
        ),
    )

    fun pacotes(): List<PacoteVozes> = models.map { model ->
        val voice = VoiceOption(
            id = "${model.id}::${if (model.language.startsWith("pt", ignoreCase = true)) "main-pt" else "main"}",
            name = "${model.name} (Piper ${model.quality})",
            language = model.language,
            isMale = false,
            engine = "kokoro",
            description = "Piper · Sherpa-ONNX offline · ${model.licenseInfo}",
            generoConhecido = false
        )
        PacoteVozes(
            id = model.id,
            engine = "kokoro",
            nomeExibicao = "${model.name} · Piper · ${model.language}",
            tamanhoDownloadMb = model.sizeMb,
            vozes = listOf(voice),
            isPronto = { context -> isReady(context, model.id) },
            tamanhoOcupadoBytes = { context -> tamanhoOcupado(context, model.id) },
            download = { context, progresso -> download(context, model.id, progresso) },
            delete = { context -> delete(context, model.id) }
        )
    }

    fun licenseNotice(model: Model, context: Context? = null): String {
        if (context != null && model.noticeResource != null && model.datasetName != null) {
            return context.getString(model.noticeResource, model.datasetName)
        }
        return when (model.licenseInfo) {
        "CC BY-NC-SA 4.0" -> "CC BY-NC-SA 4.0 · uso não comercial, atribuição e mesma licença"
        "MIT / CC BY 4.0" -> "MIT · corpus CC BY 4.0 com atribuição"
        else -> model.licenseInfo
        }
    }
    fun isReady(context: Context, packId: String): Boolean {
        val model = models.firstOrNull { it.id == packId } ?: return false
        val dir = dirBundle(context, model.id)
        val manifest = BYOMManager.lerManifesto(dir) ?: return false
        return manifest.arquitetura == "vits" &&
            manifest.vozes.singleOrNull()?.idOriginal == "main" &&
            BYOMManager.validarConteudo(dir, manifest) == null &&
            File(dir, "espeak-ng-data").list()?.size?.let { it >= ESPEAK_MIN_FILES } == true
    }

    fun tamanhoOcupado(context: Context, packId: String): Long {
        val dir = dirBundle(context, packId)
        return if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    fun delete(context: Context, packId: String) {
        dirBundle(context, packId).deleteRecursively()
        File(context.filesDir, "$packId.tar.bz2").delete()
        File(context.filesDir, "$packId.tar.bz2.part").delete()
        BYOMManager.invalidarCachePacote(packId)
    }

    suspend fun download(context: Context, packId: String, onProgress: ProgressoDownload) =
        withContext(Dispatchers.IO) {
            val model = models.firstOrNull { it.id == packId }
                ?: throw IOException("Pacote Piper desconhecido: $packId")
            val archive = File(context.filesDir, "${model.id}.tar.bz2")
            val staging = File(context.cacheDir, "${model.id}-staging")
            val destination = dirBundle(context, model.id)
            try {
                staging.deleteRecursively()
                destination.deleteRecursively()
                val url = "$RELEASE_URL/${model.archive}.tar.bz2"
                val total = SupertonicAssetManager.probeFileSize(url)
                if (total <= 0L || total > MAX_ARCHIVE_BYTES) {
                    throw IOException("Tamanho de pacote Piper inválido (${total / MB} MB).")
                }
                onProgress("Baixando ${model.name} (Piper)", 0f, 0L, total)
                var downloaded = File(archive.parentFile, "${archive.name}.part").let { if (it.exists()) it.length() else 0L }
                var lastProgress = 0L
                SupertonicAssetManager.downloadFileWithResume(url, archive) { bytes ->
                    downloaded += bytes
                    val now = System.currentTimeMillis()
                    if (now - lastProgress > INTERVALO_PROGRESSO_MS) {
                        onProgress("Baixando ${model.name} (Piper)", (downloaded.toFloat() / total).coerceIn(0f, 1f), downloaded, total)
                        lastProgress = now
                    }
                }
                if (archive.length() != total) throw IOException("Download Piper incompleto.")
                com.jonjonesbr.audiobookgen.util.PythonInicio.garantir(context)
                val python = Python.getInstance().getModule("piper_archive")
                python.callAttr("extract_piper_tar_bz2", archive.absolutePath, staging.absolutePath, model.onnx)

                val manifest = JSONObject().apply {
                    put("arquitetura", "vits")
                    put("nome", "${model.name} (Piper ${model.language})")
                    put("atribuicao", model.attribution)
                    put("vozes", org.json.JSONArray().put(JSONObject().apply {
                        put("id", "main")
                        put("nome", model.name)
                        put("idioma", model.language)
                        put("sid", 0)
                    }))
                }
                File(staging, "manifesto.json").writeText(manifest.toString(), Charsets.UTF_8)
                val parsed = BYOMManager.lerManifesto(staging)
                    ?: throw IOException("Manifesto do pacote ${model.name} inválido.")
                BYOMManager.validarConteudo(staging, parsed)?.let { throw IOException("Pacote ${model.name} incompleto: $it") }
                if (File(staging, "espeak-ng-data").list()?.size?.let { it >= ESPEAK_MIN_FILES } != true) {
                    throw IOException("Dados espeak-ng incompletos no pacote ${model.name}.")
                }
                destination.parentFile?.mkdirs()
                if (!staging.renameTo(destination)) staging.copyRecursively(destination, overwrite = true)
                if (!isReady(context, model.id)) throw IOException("Pacote ${model.name} falhou na verificação final.")
                BYOMManager.rehidratarPacote(context, model.id)
                archive.delete()
                onProgress("Pronto", 1f, total, total)
            } catch (error: Exception) {
                destination.deleteRecursively()
                staging.deleteRecursively()
                archive.delete()
                Log.e(TAG, "Falha no download Piper ${model.name}: ${error.message}", error)
                throw error
            }
        }

    private fun dirBundle(context: Context, packId: String): File =
        File(BYOMManager.dirImportados(context), packId)
}
