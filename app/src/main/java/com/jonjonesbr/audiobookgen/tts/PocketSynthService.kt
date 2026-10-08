package com.jonjonesbr.audiobookgen.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Persistent, serialized native Pocket engine hosted outside the UI/player process. */
class PocketSynthService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineLock = Any()
    private val activeCalls = AtomicInteger(0)
    private var activePackId: String? = null
    private var activeHasEncoder = false
    private var activeLsdSteps = DEFAULT_LSD_STEPS
    private var engine: NativePocketTts? = null
    private var idleRelease: Runnable? = null

    private val binder = object : IPocketSynthService.Stub() {
        override fun synthesize(text: String, voiceId: String, outPath: String, lsdSteps: Int): String {
            val shouldStartForeground = activeCalls.getAndIncrement() == 0
            try {
                if (shouldStartForeground) startSynthesisForeground()
                synchronized(engineLock) {
                    val spec = PocketTtsModelManager.specForVoice(voiceId)
                        ?: return "voz Pocket não reconhecida: $voiceId"
                    if (!PocketTtsModelManager.isReady(this@PocketSynthService, spec.id)) {
                        return "pacote Pocket ${spec.languageTag} não está instalado"
                    }
                    val pack = PocketTtsModelManager.packDir(this@PocketSynthService, spec.id)
                        ?: return "diretório do pacote Pocket inválido"
                    val custom = PocketCustomVoices.isCustom(voiceId)
                    val voice: File
                    if (custom) {
                        voice = PocketCustomVoices.file(this@PocketSynthService, voiceId)
                            ?: return "a voz clonada não foi encontrada no aparelho"
                        if (!PocketEncoderManager.installInto(this@PocketSynthService, spec.languageTag, pack)) {
                            return "prepare a clonagem deste idioma (Ajustes → Vozes clonadas)"
                        }
                    } else {
                        voice = File(File(pack, "voices"), spec.voiceFile)
                    }
                    val native = getEngine(spec.id, pack, lsdSteps)
                    val output = File(outPath)
                    output.parentFile?.mkdirs()
                    if (!native.synthesize(text, voice.absolutePath, output.absolutePath)) {
                        output.delete()
                        return "o Pocket TTS não gerou áudio válido"
                    }
                    if (!output.isFile || output.length() < MIN_AUDIO_BYTES) {
                        output.delete()
                        return "WAV Pocket inválido (size=${output.length()})"
                    }
                    return ""
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Pocket synthesis failed (voice=$voiceId): ${error.message}", error)
                return error.message ?: error.javaClass.simpleName
            } finally {
                if (activeCalls.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    synchronized(engineLock) {
                        if (engine != null) scheduleIdleRelease()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        idleRelease?.let(mainHandler::removeCallbacks)
        synchronized(engineLock) {
            engine?.close()
            engine = null
            activePackId = null
        }
        super.onDestroy()
    }

    private fun getEngine(packId: String, root: File, lsdSteps: Int): NativePocketTts {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = null
        // Passos de decodificação só valem na criação do motor: mudar o valor recria o motor.
        val temCodificador = File(File(root, "models"), PocketEncoderManager.FILE_NAME).isFile
        engine?.takeIf {
            activePackId == packId && activeLsdSteps == lsdSteps && activeHasEncoder == temCodificador
        }?.let { return it }
        engine?.close()
        engine = NativePocketTts(
            modelsDir = File(root, "models").absolutePath,
            voicesDir = File(root, "voices").absolutePath,
            precision = "int8",
            lsdSteps = lsdSteps,
            threads = DEFAULT_THREADS,
            sentencePauseMs = SENTENCE_PAUSE_MS
        ).also {
            activePackId = packId
            activeLsdSteps = lsdSteps
            activeHasEncoder = temCodificador
        }
        return engine!!
    }

    private fun scheduleIdleRelease() {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = Runnable {
            synchronized(engineLock) {
                engine?.close()
                engine = null
                activePackId = null
                idleRelease = null
                Log.i(TAG, "Released idle Pocket model to reduce background memory use")
            }
        }.also { mainHandler.postDelayed(it, IDLE_RELEASE_DELAY_MS) }
    }

    private fun startSynthesisForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Síntese Pocket TTS", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(com.jonjonesbr.audiobookgen.R.string.notif_sintetizando_titulo))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "PocketSynthService"
        const val CHANNEL_ID = "lylyreader_pocket_synth"
        const val NOTIFICATION_ID = 2003
        const val MIN_AUDIO_BYTES = 500L
        const val DEFAULT_THREADS = 2
        const val DEFAULT_LSD_STEPS = 1
        // Pausa entre frases DENTRO de um mesmo trecho (a guiada agrupa frases curtas). Igual ao
        // padrão de "Pausa entre frases" dos Ajustes; este serviço roda em outro processo e não
        // acompanha o slider.
        const val SENTENCE_PAUSE_MS = 500
        const val IDLE_RELEASE_DELAY_MS = 60_000L
    }
}
