package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import java.io.File

/** Synchronous Chaquopy bridge for audiobook generation; shares the cache with guided reading. */
object PocketSynthBridge {
    private const val TAG = "PocketSynthBridge"
    @Volatile private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun synthesize(text: String, voiceId: String, outPath: String): Boolean {
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
