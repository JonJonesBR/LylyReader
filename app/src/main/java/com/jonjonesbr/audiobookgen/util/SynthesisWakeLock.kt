package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.os.PowerManager
import android.util.Log

object SynthesisWakeLock {
    private const val TAG = "SynthesisWakeLock"
    private const val WAKE_LOCK_TAG = "LylyReader:SynthesisWakeLock"
    private const val ACQUIRE_TIMEOUT_MS = 10 * 60 * 1000L
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock?.setReferenceCounted(false)
        }
        wakeLock?.acquire(ACQUIRE_TIMEOUT_MS)
        Log.d(TAG, "WakeLock acquired")
    }

    @Synchronized
    fun release() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
    }
}
