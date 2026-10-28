package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.os.Looper
import android.util.Log
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import java.io.File

/** Synchronous Chaquopy bridge for audiobook generation; shares the cache with guided reading. */
object PocketSynthBridge {
    private const val TAG = "PocketSynthBridge"
    @Volatile private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Ajustes que mudam o áudio Pocket sem entrar no hash do job: gravados no progress.json para a retomada. */
    @JvmStatic
    fun assinaturaAjustes(): String {
        val context = appContext ?: return ""
        val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs(context)
        return "pk=${prefs.pocketPassos};t=${prefs.pocketExpressividade}"
    }

    @JvmStatic
    fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // A síntese usa runBlocking em PocketTtsEngine: chamar na main thread trava o app.
            CrashLogWriter.log(
                IllegalStateException("PocketSynthBridge.synthesize chamado na main thread"),
                TAG,
                "Síntese bloqueada na main thread (voz=$voiceId)"
            )
            return false
        }
        val context = appContext ?: return false
        if (PocketTtsModelManager.specForVoice(voiceId) == null) return false
        return try {
            val speed = com.jonjonesbr.audiobookgen.data.AppPrefs(context).ritmo
            TtsChunkCache.synthesizeSync(context, voiceId, speed, text, File(outPath)) { temporary ->
                kotlinx.coroutines.runBlocking {
                    PocketTtsEngine(context).synthesize(text, voiceId, temporary).isSuccess
                }
            }.also { if (!it) Log.w(TAG, "Pocket synthesis failed for voice=$voiceId") }
        } catch (error: Throwable) {
            Log.e(TAG, "Pocket synthesis error (${voiceId}): ${error.message}", error)
            false
        }
    }
}
