package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Guided-reading adapter; native inference runs only inside the isolated :pocket service. */
class PocketTtsEngine(private val context: Context) {
    @Volatile private var lastError: String? = null

    fun ultimoErro(): String? = lastError

    suspend fun synthesize(text: String, voiceId: String, output: File): Result<File> =
        withContext(Dispatchers.IO) {
            // Sem nenhuma letra/dígito (ex.: "***") o Pocket leria os símbolos: entrega um silêncio curto.
            if (text.none { it.isLetterOrDigit() }) {
                output.parentFile?.mkdirs()
                output.writeBytes(SilentWav.bytes(SILENCE_MS))
                lastError = null
                return@withContext Result.success(output)
            }
            val spec = PocketTtsModelManager.specForVoice(voiceId)
                ?: return@withContext failure("Voz Pocket não reconhecida: $voiceId")
            if (!PocketTtsModelManager.isReady(context, spec.id)) {
                return@withContext failure("Baixe o pacote Pocket ${spec.languageTag} em Vozes Offline.")
            }
            if (PocketCustomVoices.isCustom(voiceId)) {
                if (PocketCustomVoices.file(context, voiceId) == null) {
                    return@withContext failure("A voz clonada não foi encontrada no aparelho.")
                }
                if (!PocketEncoderManager.isInstalled(context, spec.languageTag)) {
                    return@withContext failure("Prepare a clonagem deste idioma em Ajustes → Vozes clonadas.")
                }
            }
            val error = PocketProcessClient.synthesize(context, text, voiceId, output)
            if (error != null) failure(error) else {
                lastError = null
                Result.success(output)
            }
        }

    private fun failure(message: String): Result<File> {
        lastError = message
        Log.e(TAG, "Pocket TTS synthesis failed: $message")
        return Result.failure(IllegalStateException(message))
    }

    private companion object {
        const val TAG = "PocketTtsEngine"
        const val SILENCE_MS = 400
    }
}
