package com.jonjonesbr.audiobookgen.tts
import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import java.io.File
/** Ponte Python para síntese ElevenLabs na conversão, com cache compartilhado com a guiada. */
object ElevenLabsSynthBridge {
    private const val TAG = "ElevenLabsSynthBridge"
    private const val PREFIXO_VOZ = "elevenlabs-"
    @Volatile private var appContext: Context? = null
    @Volatile private var ultimoMotivo: String? = null
    private val engine = ElevenLabsTtsEngine()
    @JvmStatic fun init(context: Context) { appContext = context.applicationContext }
    @JvmStatic fun ultimoMotivo(): String? = ultimoMotivo
    @JvmStatic fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
        val ctx = appContext ?: return false
        return try {
            val ritmo = AppPrefs(ctx).ritmo
            val apiKey = SecurePreferences.getElevenLabsKey(ctx)
            val vozReal = voiceId.removePrefix(PREFIXO_VOZ)
            val ok = TtsChunkCache.synthesizeSync(ctx, voiceId, ritmo, text, File(outPath)) { temporary ->
                val result = engine.synthesizeSync(text, vozReal, apiKey, ritmo, temporary)
                if (result.isFailure) {
                    ultimoMotivo = result.exceptionOrNull()?.message ?: "motivo desconhecido"
                }
                result.isSuccess
            }
            if (!ok && ultimoMotivo == null) ultimoMotivo = "falha ao sintetizar áudio válido"
            ok
        } catch (t: Throwable) {
            ultimoMotivo = t.message ?: "motivo desconhecido"
            Log.e(TAG, "Erro na síntese ($voiceId): " + t.message, t)
            false
        }
    }
}