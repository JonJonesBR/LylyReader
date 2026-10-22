package com.jonjonesbr.audiobookgen.tts
import android.content.Context
import android.os.Looper
import android.util.Log
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import kotlinx.coroutines.runBlocking
import java.io.File
/** Ponte Python para TextToSpeech Android na conversão; o cache também é usado pela guiada. */
object AndroidSynthBridge {
    private const val TAG = "AndroidSynthBridge"
    private const val RITMO_BASE = 100f
    @Volatile private var appContext: Context? = null
    @JvmStatic fun init(context: Context) { appContext = context.applicationContext }
    @JvmStatic fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // AndroidTtsEngineCache usa withContext(Main): chamar daqui trava a thread principal.
            CrashLogWriter.log(
                IllegalStateException("AndroidSynthBridge.synthesize chamado na main thread"),
                TAG,
                "Síntese bloqueada na main thread (voz=$voiceId)"
            )
            return false
        }
        val ctx = appContext ?: return false
        return try {
            val ritmo = com.jonjonesbr.audiobookgen.data.AppPrefs(ctx).ritmo
            val speed = ritmo / RITMO_BASE
            TtsChunkCache.synthesizeSync(ctx, voiceId, ritmo, text, File(outPath)) { temporary ->
                runBlocking {
                    AndroidTtsEngineCache.synthesize(ctx, voiceId, text, temporary, speed).isSuccess
                }
            }.also { if (!it) Log.w(TAG, "Síntese Android falhou para voz=$voiceId") }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro na síntese ($voiceId): " + t.message, t)
            false
        }
    }
}