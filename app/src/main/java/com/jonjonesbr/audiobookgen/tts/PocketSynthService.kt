package com.jonjonesbr.audiobookgen.tts

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
    private var activeTemperature = DEFAULT_TEMPERATURE
    private var activeThreads = PocketThreads.PADRAO
    private var engine: NativePocketTts? = null
    private var idleRelease: Runnable? = null

    // Primeiro plano do processo (rb-36): entra na primeira síntese e sai 60 s depois da última.
    // rb-37: o tipo é mediaPlayback quando a leitura guiada está ativa (paraReproducao), no Android 15+.
    private val motor = PrimeiroPlanoDoMotor(this, NOTIFICATION_ID)

    private val binder = object : IPocketSynthService.Stub() {
        override fun synthesize(
            text: String,
            voiceId: String,
            outPath: String,
            lsdSteps: Int,
            temperature: Float,
            threads: Int,
            paraReproducao: Boolean
        ): String {
            activeCalls.incrementAndGet()
            motor.comecar(paraReproducao)
            try {
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
                    val native = getEngine(spec.id, pack, lsdSteps, temperature, threads)
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
                motor.terminar()
                if (activeCalls.decrementAndGet() == 0) {
                    synchronized(engineLock) {
                        if (engine != null) scheduleIdleRelease()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Android 15+: limite de tempo do dataSync atingido. Sai do foreground sem parar o serviço
    // (continua ligado ao cliente); sem isto o sistema derruba o app.
    override fun onTimeout(startId: Int, fgsType: Int) {
        motor.aoTimeout()
    }

    override fun onDestroy() {
        motor.encerrar()
        idleRelease?.let(mainHandler::removeCallbacks)
        synchronized(engineLock) {
            engine?.close()
            engine = null
            activePackId = null
        }
        super.onDestroy()
    }

    private fun getEngine(
        packId: String,
        root: File,
        lsdSteps: Int,
        temperature: Float,
        threads: Int
    ): NativePocketTts {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = null
        // Passos, temperatura e threads só valem na criação do motor: mudar um deles recria o motor.
        val temCodificador = File(File(root, "models"), PocketEncoderManager.FILE_NAME).isFile
        engine?.takeIf {
            activePackId == packId && activeLsdSteps == lsdSteps && activeTemperature == temperature &&
                activeThreads == threads && activeHasEncoder == temCodificador
        }?.let { return it }
        engine?.close()
        Log.i(TAG, "Criando motor Pocket: threads=$threads, passos=$lsdSteps")
        engine = NativePocketTts(
            modelsDir = File(root, "models").absolutePath,
            voicesDir = File(root, "voices").absolutePath,
            precision = "int8",
            temperature = temperature,
            lsdSteps = lsdSteps,
            threads = threads,
            sentencePauseMs = SENTENCE_PAUSE_MS
        ).also {
            activePackId = packId
            activeLsdSteps = lsdSteps
            activeTemperature = temperature
            activeThreads = threads
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

    private companion object {
        const val TAG = "PocketSynthService"
        const val NOTIFICATION_ID = 2003
        const val MIN_AUDIO_BYTES = 500L
        const val DEFAULT_LSD_STEPS = 1
        const val DEFAULT_TEMPERATURE = 0.1f
        // Pausa entre frases DENTRO de um mesmo trecho (a guiada agrupa frases curtas). Igual ao
        // padrão de "Pausa entre frases" dos Ajustes; este serviço roda em outro processo e não
        // acompanha o slider.
        const val SENTENCE_PAUSE_MS = 500
        const val IDLE_RELEASE_DELAY_MS = 60_000L
    }
}
