package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Checagem de conectividade usada ANTES de iniciar operações que dependem de motores
 * TTS online (Edge/Gemini/OpenRouter). Os motores "android" (TTS do dispositivo) e
 * "onnx" (Supertonic) sintetizam offline e não devem ser bloqueados por esta checagem.
 */
object NetworkStatus {

    private val OFFLINE_ENGINES = setOf("android", "onnx", "kokoro", "pocket")

    fun engineRequiresNetwork(engine: String): Boolean = engine !in OFFLINE_ENGINES

    /** True quando a rede ativa é Wi-Fi. Indeterminado (sem ConnectivityManager) = true,
     * para não assustar o usuário com aviso desnecessário. */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // Sem como checar: assume online e deixa a operação falhar com o erro real.
            ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
