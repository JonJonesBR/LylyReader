package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech

/** Motor TTS instalado no aparelho: pacote real + rótulo amigável para exibir na UI. */
data class AndroidEngineInfo(val packageName: String, val label: String)

/**
 * Descobre os motores TTS instalados via `PackageManager`, sem tocar em `TextToSpeech` —
 * por isso não tem custo de inicialização nem risco de threading, diferente de
 * [AndroidTtsEngineCache].
 */
object AndroidTtsEngineDiscovery {
    fun listEngines(context: Context): List<AndroidEngineInfo> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfos = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
                AndroidEngineInfo(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
    }
}
