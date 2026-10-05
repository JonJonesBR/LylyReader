package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gerencia o download e verificação dos modelos TTS offline (ONNX).
 *
 * Estrutura de diretórios no filesDir:
 *   onnx_models/
 *     supertonic-m1/
 *       supertonic.onnx
 *       voices/M1.json
 */
class OnnxModelManager(private val context: Context) {
    private val TAG = "OnnxModelManager"

    // ──────────────────────────────────────────────────────────────────────────
    //  Verificação de modelo já baixado
    // ──────────────────────────────────────────────────────────────────────────

    fun isModelDownloaded(voiceId: String): Boolean {
        return when {
            // Supertonic usa o pacote V2 multilíngue em filesDir/v2 — o mesmo local que a
            // síntese (OnnxTtsEngine.synthesizeSupertonicNative) e isAvailable leem. Delega
            // ao SupertonicAssetManager para evitar divergência de local/versão (#bug).
            voiceId.startsWith("supertonic-") ->
                SupertonicAssetManager.isVersionReady(context, "v2")

            else -> false
        }
    }

    fun modelDir(voiceId: String): File {
        val dirName = when {
            voiceId.startsWith("supertonic-") -> "supertonic-m1"
            else -> voiceId
        }
        return File(context.filesDir, "onnx_models/$dirName")
    }

    fun getModelFile(voiceId: String, filename: String): File? {
        val file = File(modelDir(voiceId), filename)
        return if (file.exists()) file else null
    }

    fun deleteModel(voiceId: String): Boolean {
        if (voiceId.startsWith("supertonic-")) {
            // Supertonic vive em filesDir/v2 (ver downloadModel/isModelDownloaded).
            SupertonicAssetManager.deleteVersion(context, "v2")
            return true
        }
        return modelDir(voiceId).deleteRecursively()
    }

    fun getOfflineVoicesSize(): Long {
        var totalSize = 0L
        val onnxModelsDir = File(context.filesDir, "onnx_models")
        if (onnxModelsDir.exists()) {
            totalSize += getDirectorySize(onnxModelsDir)
        }
        val v2Dir = File(context.filesDir, "v2")
        if (v2Dir.exists()) {
            totalSize += getDirectorySize(v2Dir)
        }
        // Bundle do motor Kokoro (V6 T1.4) — mesmo card "Vozes offline" de Ajustes.
        totalSize += KokoroModelManager.tamanhoOcupado(context)
        return totalSize
    }

    private fun getDirectorySize(directory: File): Long {
        var size = 0L
        val files = directory.listFiles() ?: return 0L
        for (file in files) {
            if (file.isDirectory) {
                size += getDirectorySize(file)
            } else {
                size += file.length()
            }
        }
        return size
    }

    fun deleteAllOfflineModels() {
        val onnxModelsDir = File(context.filesDir, "onnx_models")
        if (onnxModelsDir.exists()) {
            onnxModelsDir.deleteRecursively()
        }
        val v2Dir = File(context.filesDir, "v2")
        if (v2Dir.exists()) {
            v2Dir.deleteRecursively()
        }
        SupertonicAssetManager.copyBundledVoiceStyles(context)
        // Bundle do motor Kokoro (V6 T1.4) — "Apagar vozes offline" também remove o kokoro.
        KokoroModelManager.delete(context)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Download de modelos
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun downloadModel(voiceId: String, onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dir = modelDir(voiceId)
                if (!dir.exists()) dir.mkdirs()

                when {
                    voiceId.startsWith("supertonic-") -> {
                        // Baixa o pacote V2 multilíngue para filesDir/v2 (local lido pela
                        // síntese). O download anterior (downloadSupertonic → V3 em
                        // onnx_models/supertonic-m1) gravava num local que a síntese nunca lê.
                        SupertonicAssetManager.downloadV2(context) { _, pct, _, _ ->
                            onProgress((pct * 100).toInt().coerceIn(0, 100))
                        }
                    }
                    else -> throw IllegalArgumentException("Voice ID não suportado: $voiceId")
                }

                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelamento do usuário não é falha: repassa para o coroutine morrer limpo,
                // senão o chamador continuaria e mostraria toast de "download falhou".
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao baixar modelo $voiceId", e)
                Result.failure(e)
            }
        }
}
