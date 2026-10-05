package com.jonjonesbr.audiobookgen.tts
import android.content.Context
import android.util.Log
import java.io.File
/** Ponte Python para síntese Kotlin Supertonic na conversão de audiobook. */
object OnnxSynthBridge {
    private const val TAG = "OnnxSynthBridge"
    @Volatile private var appContext: Context? = null
    @Volatile private var engine: OnnxTtsEngine? = null
    @JvmStatic fun init(context: Context) { appContext = context.applicationContext }
    @Synchronized private fun engine(): OnnxTtsEngine? {
        val ctx = appContext ?: return null
        return engine ?: OnnxTtsEngine(ctx).also { engine = it }
    }
    @JvmStatic fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
        val ctx = appContext ?: return false
        val eng = engine() ?: return false
        return try {
            val speed = com.jonjonesbr.audiobookgen.data.AppPrefs(ctx).ritmo
            TtsChunkCache.synthesizeSync(ctx, voiceId, speed, text, File(outPath)) { temporary ->
                eng.synthesizeSync(text, voiceId, temporary)
            }.also { if (!it) Log.w(TAG, "Síntese ONNX falhou para voz=$voiceId") }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro na síntese ($voiceId): " + t.message, t)
            false
        }
    }
}