package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.tts.ISupertonicSynthService
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File

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
 *
 * rb-36: o primeiro plano agora tem histerese. Entra na primeira síntese e sai 60 s depois da
 * última, sem liga/desliga a cada chamada; canal e notificação são os do [PrimeiroPlanoDoMotor].
 */
class SupertonicSynthService : Service() {

    private val TAG = "SupertonicSynthSvc"

    // Reutiliza um único engine no processo filho (mantém o nativo inicializado entre chamadas).
    private val engine by lazy { OnnxTtsEngine(applicationContext, allowIsolatedProcessDelegation = false) }

    // Primeiro plano do processo (rb-36): comecar() antes de cada síntese, terminar() depois.
    private val motor = PrimeiroPlanoDoMotor(this, NOTIF_ID)

    private val binder = object : ISupertonicSynthService.Stub() {
        override fun synthesize(text: String, voiceId: String, outPath: String): String {
            motor.comecar()
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
                motor.terminar()
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
        super.onDestroy()
    }

    private companion object {
        const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
        const val NOTIF_ID = 2002
    }
}
