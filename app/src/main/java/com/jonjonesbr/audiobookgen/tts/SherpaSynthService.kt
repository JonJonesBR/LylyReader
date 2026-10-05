package com.jonjonesbr.audiobookgen.tts

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
 * Roda no processo isolado `:sherpa` (ver AndroidManifest).
 *
 * Hospeda a síntese Kokoro-82M via Sherpa-ONNX (ONNX Runtime embutido no
 * libsherpa-onnx-jni.so — variante static-link do AAR). Mesma justificativa do
 * [SupertonicSynthService] (RN-2 do PLANO_MELHORIAS_V6): a inferência nativa do ONNX Runtime
 * pode chamar `abort()`/segfault em erro fatal, impossível de capturar em Kotlin; isolada
 * aqui, um crash mata apenas este processo — o app principal recebe DeadObjectException e
 * segue funcionando (conversão, playback e UI intactos).
 *
 * Foreground service enquanto sintetiza (achado real em device, 2026-09-16): o gerenciador de
 * processos em segundo plano do MIUI ("SmartPower", proprietário) congela este processo por não
 * ter nenhuma superfície própria (sem Activity, sem notificação) — mesmo com o app principal em
 * primeiro plano e `BIND_ABOVE_CLIENT` no bindService (tentado antes, confirmado insuficiente:
 * o mesmo congelamento se repetiu em teste real). `startForeground()`/`stopForeground()` giram
 * em torno da contagem de chamadas em voo — sem notificação nenhuma quando ocioso.
 */
class SherpaSynthService : Service() {

    // Reutiliza um único engine no processo filho (sessão ORT persistente — o modelo de
    // ~114MB NÃO é recarregado entre chunks). allowIsolatedProcessDelegation=false evita
    // recursão (o serviço JÁ é o processo isolado).
    private val engine by lazy {
        KokoroTtsEngine(applicationContext, allowIsolatedProcessDelegation = false)
    }

    // Contador de chamadas synthesize() em voo (até 2 concorrentes — SherpaProcessClient usa
    // Semaphore(2)). 0 → 1 entra em foreground; volta a 0 → sai do foreground.
    private val chamadasEmVoo = AtomicInteger(0)

    // Fronteira com nativo: exceção ampla intencional (mesmo motivo do KokoroTtsEngine).
    @Suppress("TooGenericExceptionCaught")
    private val binder = object : ISherpaSynthService.Stub() {
        override fun synthesize(text: String, voiceId: String, outPath: String): String {
            if (chamadasEmVoo.getAndIncrement() == 0) iniciarForeground()
            try {
                val out = File(outPath)
                val result = runBlocking { engine.synthesize(text, voiceId, out) }
                return when {
                    result.isFailure ->
                        result.exceptionOrNull()?.message ?: "falha desconhecida na síntese"
                    !out.exists() || out.length() < TAMANHO_MIN_ARQUIVO_VALIDO ->
                        "WAV inválido gerado (size=${out.length()})"
                    else -> ""
                }
            } catch (t: Throwable) {
                // Qualquer exceção Kotlin/Java é contida aqui; aborts nativos matam só este
                // processo.
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
        const val TAG = "SherpaSynthSvc"
        const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
        const val CHANNEL_ID = "lylyreader_sherpa_synth"
        const val NOTIF_ID = 2001
    }
}
