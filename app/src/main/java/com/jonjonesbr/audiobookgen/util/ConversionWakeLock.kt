package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * WakeLock da conversão em background. Ao contrário do [SynthesisWakeLock] (prévia e parágrafo), é renovável:
 * o worker chama [renovar] durante a conversão, e cada aquisição vale por [TIMEOUT_MS].
 * Nunca lança exceção: falha de energia não pode derrubar a conversão.
 */
object ConversionWakeLock {
    private const val TAG = "ConversionWakeLock"
    private const val WAKE_LOCK_TAG = "LylyReader:Conversao"
    private const val TIMEOUT_MS = 30 * 60 * 1000L
    private var wakeLock: PowerManager.WakeLock? = null

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
    }
}
