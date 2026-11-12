package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.DiagnosticoSintese
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Adaptador da leitura guiada para as vozes Paradee; a inferência nativa roda só no processo :paradee. */
class ParadeeTtsEngine(private val context: Context) {
    @Volatile private var lastError: String? = null

    fun ultimoErro(): String? = lastError

    suspend fun synthesize(texto: String, voiceId: String, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) {
            // Sem nenhuma letra/dígito (ex.: "***") o motor leria os símbolos: entrega um silêncio curto.
            if (texto.none { it.isLetterOrDigit() }) {
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(SilentWav.bytes(SILENCE_MS))
                lastError = null
                return@withContext Result.success(outputFile)
            }
            if (ParadeeVozes.spec(voiceId) == null) {
                return@withContext failure("Voz Paradee não reconhecida: $voiceId")
            }
            if (!ParadeeModelManager.isReady(context)) {
                return@withContext failure("Baixe as vozes Paradee em Downloads.")
            }
            // Mesma escala do Kokoro: o ritmo em % (100 = normal) vira multiplicador de velocidade.
            val speed = AppPrefs(context).ritmo / RITMO_PORCENTO_BASE
            var error: String? = null
            for (tentativa in 1..MAX_TENTATIVAS) {
                val inicioNs = System.nanoTime()
                error = ParadeeProcessClient.synthesize(context, texto, voiceId, outputFile, speed)
                DiagnosticoSintese.registrar(
                    context, "paradee.chamada",
                    "voz" to voiceId, "chars" to texto.length, "ms" to (System.nanoTime() - inicioNs) / NS_POR_MS,
                    "bytes" to outputFile.length(), "erro" to error, "tentativa" to tentativa, "texto" to texto
                )
                if (error != ParadeeProcessClient.MENSAGEM_PROCESSO_ENCERRADO) break
                if (tentativa < MAX_TENTATIVAS) delay(ESPERA_RETENTATIVA_MS)
            }
            if (error != null) failure(error) else {
                lastError = null
                Result.success(outputFile)
            }
        }

    private fun failure(message: String): Result<File> {
        lastError = message
        Log.e(TAG, "Paradee TTS synthesis failed: $message")
        return Result.failure(IllegalStateException(message))
    }

    private companion object {
        const val TAG = "ParadeeTtsEngine"
        const val SILENCE_MS = 400
        const val NS_POR_MS = 1_000_000L
        const val RITMO_PORCENTO_BASE = 100f
        const val MAX_TENTATIVAS = 2
        const val ESPERA_RETENTATIVA_MS = 1_000L
    }
}
