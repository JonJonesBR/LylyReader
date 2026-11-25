package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * WakeLock e WifiLock da conversão em background. Ao contrário do [SynthesisWakeLock] (prévia e parágrafo), é
 * renovável: o worker chama [renovar] durante a conversão, e cada aquisição vale por [TIMEOUT_MS].
 * Nunca lança exceção: falha de energia ou Wi-Fi não pode derrubar a conversão.
 */
object ConversionWakeLock {
    private const val TAG = "ConversionWakeLock"
    private const val WAKE_LOCK_TAG = "LylyReader:Conversao"
    private const val WIFI_LOCK_TAG = "LylyReader:ConversaoWifi"
    private const val TIMEOUT_MS = 30 * 60 * 1000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(context: Context) {
        try {
            val trava = wakeLock ?: run {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also { it.setReferenceCounted(false) }
            }
            wakeLock = trava
            trava.acquire(TIMEOUT_MS)
            Log.d(TAG, "WakeLock adquirido")
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao adquirir WakeLock", e)
        }
        adquirirWifiLock(context)
    }

    /** Reinicia o prazo de [TIMEOUT_MS] se a trava estiver segurando; não faz nada caso contrário. */
    @Synchronized
    fun renovar() {
        try {
            val trava = wakeLock
            if (trava?.isHeld == true) {
                trava.acquire(TIMEOUT_MS)
                Log.d(TAG, "WakeLock renovado")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao renovar WakeLock", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock liberado")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao liberar WakeLock", e)
        }
        liberarWifiLock()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun adquirirWifiLock(context: Context) {
        try {
            val travaWifi = wifiLock ?: run {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return
                val modo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wm.createWifiLock(modo, WIFI_LOCK_TAG).also { it.setReferenceCounted(false) }
            }
            wifiLock = travaWifi
            if (!travaWifi.isHeld) {
                travaWifi.acquire()
                Log.d(TAG, "WifiLock adquirido")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao adquirir WifiLock", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun liberarWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock liberado")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao liberar WifiLock", e)
        }
    }
}
