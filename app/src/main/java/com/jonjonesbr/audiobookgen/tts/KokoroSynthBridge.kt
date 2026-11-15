package com.jonjonesbr.audiobookgen.tts
import android.content.Context
import android.util.Log
import java.io.File
/** Ponte Python para síntese Kotlin Kokoro/BYOM na conversão de audiobook. */
object KokoroSynthBridge {
    private const val TAG = "KokoroSynthBridge"
    @Volatile private var appContext: Context? = null
    @Volatile private var engine: KokoroTtsEngine? = null
    @JvmStatic fun init(context: Context) { appContext = context.applicationContext }
    @Synchronized private fun engine(): KokoroTtsEngine? {
        val ctx = appContext ?: return null
        return engine ?: KokoroTtsEngine(ctx).also { engine = it }
    }
    @JvmStatic fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
        val ctx = appContext ?: return false
        val eng = engine() ?: return false
        val vozEfetiva = ParadeeVozes.migrarVozKokoroRemovida(voiceId)
        return try {
            val speed = com.jonjonesbr.audiobookgen.data.AppPrefs(ctx).ritmo
            TtsChunkCache.synthesizeSync(ctx, vozEfetiva, speed, text, File(outPath)) { temporary ->
                eng.synthesizeSync(text, vozEfetiva, temporary)
            }.also { if (!it) Log.w(TAG, "Síntese Kokoro falhou para voz=$vozEfetiva") }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro na síntese ($vozEfetiva): " + t.message, t)
            false
        }
    }
}