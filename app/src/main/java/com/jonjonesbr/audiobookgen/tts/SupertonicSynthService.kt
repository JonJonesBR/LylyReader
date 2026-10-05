package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.tts.ISupertonicSynthService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Roda no processo isolado `:supertonic` (ver AndroidManifest).
 *
 * Hospeda a síntese de voz ONNX nativa — Supertonic (Rust + ONNX Runtime) — porque pode
 * chamar `abort()`/segfault em erro fatal, impossível de capturar em Kotlin. Isolando-a aqui,
 * um crash mata apenas este processo; o app principal recebe DeadObjectException e segue
 * funcionando.
 *
 * Foreground service enquanto sintetiza (mesmo achado real em device do SherpaSynthService,
 * 2026-09-16, agora replicado aqui — nunca tinha sido diagnosticado no Supertonic por falta
 * de comparação lado a lado entre os dois clientes): o gerenciador de processos em segundo
 * plano do MIUI ("SmartPower") congela processos sem nenhuma superfície própria (sem
 * Activity, sem notificação) — mesmo com o app principal em primeiro plano.
 * `BIND_ABOVE_CLIENT` sozinho no bindService NÃO resolve isso (confirmado no Sherpa); o
 * foreground service com notificação é o que evita o congelamento de verdade.
 */
class SupertonicSynthService : Service() {

    private val TAG = "SupertonicSynthSvc"

    // Reutiliza um único engine no processo filho (mantém o nativo inicializado entre chamadas).
    private val engine by lazy { OnnxTtsEngine(applicationContext, allowIsolatedProcessDelegation = false) }

    // Contador de chamadas synthesize() em voo. 0 → 1 entra em foreground; volta a 0 → sai.
    private val chamadasEmVoo = AtomicInteger(0)

    private val binder = object : ISupertonicSynthService.Stub() {
        override fun synthesize(text: String, voiceId: String, outPath: String): String {
            if (chamadasEmVoo.getAndIncrement() == 0) iniciarForeground()
            try {
                val out = File(outPath)
                val result = runBlocking { engine.synthesize(text, voiceId, out) }
                return when {
                    result.isFailure -> result.exceptionOrNull()?.message ?: "falha desconhecida na síntese"
                    !out.exists() || out.length() < TAMANHO_MIN_ARQUIVO_VALIDO -> "WAV inválido gerado (size=${out.length()})"
                    else -> ""
                }
            } catch (t: Throwable) {
                // Qualquer exceção Kotlin/Java é contida aqui; aborts nativos matam só este processo.
                Log.e(TAG, "Synthesis failed in isolated process (voice=$voiceId): ${t.message}", t)
                return t.message ?: t.javaClass.simpleName
            } finally {
                if (chamadasEmVoo.decrementAndGet() == 0) pararForeground()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun iniciarForeground() {
        criarCanal()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(com.jonjonesbr.audiobookgen.R.string.notif_sintetizando_titulo))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun pararForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Síntese de voz", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
    }

    private companion object {
        const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
        const val CHANNEL_ID = "lylyreader_supertonic_synth"
        const val NOTIF_ID = 2002
    }
}
