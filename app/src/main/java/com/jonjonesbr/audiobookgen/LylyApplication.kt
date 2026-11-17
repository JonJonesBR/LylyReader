package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.NativeCrashDetector
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Process
import android.util.Log
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager
import com.jonjonesbr.audiobookgen.ui.CapasDaBiblioteca
import kotlin.concurrent.thread

class LylyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupGlobalExceptionHandler()

        // Os processos isolados (:supertonic, :sherpa, :pocket e :paradee) também instanciam a
        // Application. Eles precisam do handler global de exceções e do CrashLogWriter (com o
        // nome do processo no arquivo) — pular o startup pesado (cópia de modelos, detector de
        // crash, limpeza) evita trabalho duplicado e relatórios duplos.
        // O pré-carregamento é POR PROCESSO, porque cada lib nativa exige sua própria ordem:
        // - :supertonic e :pocket: forçam o soname libonnxruntime.so a apontar para a cópia 1.26.0
        //   dedicada. Se OrtEnvironment (1.20.0) carregar primeiro, o linker registra a
        //   1.20.0 do APK como libonnxruntime.so → mismatch de símbolos e abort() no init.
        // - :sherpa (Kokoro/Sherpa-ONNX): variante static-link — o onnxruntime está embutido
        //   no libsherpa-onnx-jni.so, então NÃO há libonnxruntime.so para gerenciar aqui;
        //   carregar as libs do Supertonic neste processo seria desperdício de memória.
        if (!isMainProcess()) {
            val nomeDoProcesso = currentProcessName()
            Log.i("LylyApplication", "Skipping heavy startup in isolated process: $nomeDoProcesso")
            // Um crash num processo de voz precisa de arquivo de erro próprio: sem o init aqui,
            // CrashLogWriter.log cai no "não inicializado" e a falha fica sem rastro.
            CrashLogWriter.init(this, processo = nomeDoProcesso.substringAfterLast(':'))
            if (nomeDoProcesso == "$packageName:sherpa") {
                try {
                    System.loadLibrary("sherpa-onnx-jni")
                    Log.i("LylyApplication", "sherpa-onnx preloaded successfully in :sherpa process")
                } catch (e: Throwable) {
                    Log.w("LylyApplication", "Falha ao pré-carregar lib sherpa-onnx no processo :sherpa: ${e.message}")
                }
            } else if (nomeDoProcesso == "$packageName:pocket") {
                try {
                    // Pocket usa o mesmo runtime compartilhado do Supertonic, já carregado
                    // sob o SONAME libonnxruntime.so no processo isolado.
                    System.loadLibrary("onnxruntime_supertonic")
                    System.loadLibrary("pockettts_jni")
                    Log.i("LylyApplication", "Pocket native libraries preloaded in isolated process")
                } catch (e: Throwable) {
                    Log.w("LylyApplication", "Falha ao pré-carregar libs Pocket no processo isolado: ${e.message}")
                }
            } else if (nomeDoProcesso == "$packageName:paradee") {
                carregarLibsParadee()
            } else {
                try {
                    System.loadLibrary("onnxruntime_supertonic")
                    System.loadLibrary("supertonic_tts")
                    Log.i("LylyApplication", "Supertonic native libraries preloaded successfully in isolated process")
                } catch (e: Throwable) {
                    Log.w("LylyApplication", "Falha ao pré-carregar libs supertonic no processo isolado: ${e.message}")
                }
            }
            return
        }

        // Aplica o tema escolhido pelo usuário (claro/escuro/sistema) antes de qualquer Activity.
        com.jonjonesbr.audiobookgen.util.ThemePrefs.apply(com.jonjonesbr.audiobookgen.util.ThemePrefs.load(this))
        com.jonjonesbr.audiobookgen.util.ThemePrefs.instalar(this)
        // Acompanha se o app está visível: a conversão só entra em primeiro plano quando pode (Android 12+).
        com.jonjonesbr.audiobookgen.util.VisibilidadeDoApp.registrar(this)
        // Mostra o ícone flutuante magnético nas telas sem o cartão "Gerando agora".
        com.jonjonesbr.audiobookgen.ui.BotaoFlutuanteConversao.registrar(this)
        CrashLogWriter.init(this)
        NativeCrashDetector.checkForNativeCrashes(this)
        // Disponibiliza o motor ONNX Kotlin para o Python (geração de audiobook Supertonic).
        com.jonjonesbr.audiobookgen.tts.OnnxSynthBridge.init(this)
        // Disponibiliza o motor TTS nativo Android para o Python (geração de audiobook com
        // vozes instaladas no aparelho).
        com.jonjonesbr.audiobookgen.tts.AndroidSynthBridge.init(this)
        // Disponibiliza o motor ElevenLabs (API oficial, chave própria do usuário) para o Python.
        com.jonjonesbr.audiobookgen.tts.ElevenLabsSynthBridge.init(this)
        // Disponibiliza o motor Kokoro (Sherpa-ONNX local) para o Python — geração de
        // audiobook kokoro via processo isolado :sherpa (V6, T1.3).
        com.jonjonesbr.audiobookgen.tts.KokoroSynthBridge.init(this)
        // Disponibiliza o Pocket TTS isolado à conversão de audiobook via Chaquopy.
        com.jonjonesbr.audiobookgen.tts.PocketSynthBridge.init(this)
        // Reidrata fora da thread principal (cold start mais rápido): pacotes de voz importados
        // (BYOM, V6 Parte B) e vozes clonadas do usuário (Pocket TTS). VoiceCatalog só mantém
        // pacotes locais em memória; o manifesto validado no import é a persistência real em
        // filesDir/importados/. Leituras do catálogo esperam esta reidratação terminar.
        VoiceCatalog.reidratarEmSegundoPlano {
            com.jonjonesbr.audiobookgen.tts.BYOMManager.rehydratarTodos(this)
            com.jonjonesbr.audiobookgen.tts.PocketCustomVoiceCatalog.registrarTodas(this)
        }
        thread(name = "bundled-model-copy") {
            // Estilos de voz Supertonic (F1–F5/M1–M5) embutidos no APK → filesDir/v2.
            SupertonicAssetManager.copyBundledVoiceStyles(this)
        }
        thread(name = "audio-cache-teto") {
            // Remove o bundle antigo do Kokoro-82M (~330 MB), substituído pelo Paradee.
            runCatching { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.delete(this) }
            // Mantém o cache de áudio das vozes dentro do teto definido em Ajustes.
            com.jonjonesbr.audiobookgen.tts.TtsChunkCache.aparaSeNecessario(this)
        }
    }

    /** Processo :paradee: mesmo runtime ORT do Supertonic (SONAME libonnxruntime.so) e a ponte JNI própria. */
    private fun carregarLibsParadee() {
        try {
            System.loadLibrary("onnxruntime_supertonic")
            System.loadLibrary("paradee_jni")
            Log.i("LylyApplication", "Paradee native libraries preloaded in isolated process")
        } catch (e: Throwable) {
            Log.w("LylyApplication", "Falha ao pré-carregar libs Paradee no processo isolado: ${e.message}")
        }
    }

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess()) return
        aliviarMemoriaSobPressao(level, portasAlivioPadrao())
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        if (!isMainProcess()) return
        aliviarMemoriaSobPressao(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, portasAlivioPadrao())
    }

    private fun portasAlivioPadrao() = PortasAlivioMemoria(
        reduzirBitmaps = { CapasDaBiblioteca.reduzirCache() },
        limparBitmaps = { CapasDaBiblioteca.limparCache() },
        soltarAudioMemoria = { GuidedPlaybackBridge.manager?.soltarCacheEmMemoria() },
        apararAudioDisco = {
            thread(name = "trim-audio-cache") {
                runCatching { com.jonjonesbr.audiobookgen.tts.TtsChunkCache.aparaSeNecessario(this) }
            }
        },
        coletarPythonSeIniciado = {
            if (com.chaquo.python.Python.isStarted()) {
                runCatching { com.chaquo.python.Python.getInstance().getModule("gc").callAttr("collect") }
            }
        }
    )

    private fun currentProcessName(): String {
        // API 28+: API direta; abaixo disso, varre a lista de processos pelo PID.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLogWriter.log(
                throwable = throwable,
                contextTag = "UNCAUGHT_CRASH",
                extraInfo = mapOf("thread" to thread.name, "threadGroup" to thread.threadGroup?.name.orEmpty())
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

internal data class PortasAlivioMemoria(
    val reduzirBitmaps: () -> Unit,
    val limparBitmaps: () -> Unit,
    val soltarAudioMemoria: () -> Unit,
    val apararAudioDisco: () -> Unit,
    val coletarPythonSeIniciado: () -> Unit
)

/**
 * Reage a callbacks de memória do Android no processo principal:
 * - Ignora níveis abaixo de `TRIM_MEMORY_RUNNING_LOW`.
 * - Em `TRIM_MEMORY_RUNNING_LOW` ou `TRIM_MEMORY_UI_HIDDEN`, reduz o cache de capas pela metade;
 *   nos níveis mais severos (`TRIM_MEMORY_RUNNING_CRITICAL`, `TRIM_MEMORY_BACKGROUND`, etc.), esvazia-o.
 * - Sempre solta o excesso de prefetch da leitura guiada, apara o cache de áudio em disco e pede
 *   `gc.collect()` ao Python caso a VM já esteja iniciada.
 */
@Suppress("DEPRECATION")
internal fun aliviarMemoriaSobPressao(nivel: Int, portas: PortasAlivioMemoria) {
    if (nivel < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
    val pressaoModerada = nivel == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        nivel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    if (pressaoModerada) {
        portas.reduzirBitmaps()
    } else {
        portas.limparBitmaps()
    }
    portas.soltarAudioMemoria()
    portas.apararAudioDisco()
    portas.coletarPythonSeIniciado()
}
