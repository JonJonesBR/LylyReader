package com.jonjonesbr.audiobookgen.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File

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
 * o mesmo congelamento se repetiu em teste real).
 *
 * rb-36: o primeiro plano agora tem histerese. Entra na primeira síntese e sai 60 s depois da
 * última, sem liga/desliga a cada chamada (a notificação ficava recriada e mudava de lugar na
 * lista); canal e notificação são os do [PrimeiroPlanoDoMotor], compartilhados pelos três motores.
 * rb-37: o tipo depende do uso. Com a leitura guiada ativa (paraReproducao) no Android 15+, o
 * primeiro plano é mediaPlayback, que não tem o limite de 6 h do dataSync; o resto segue dataSync.
 */
class SherpaSynthService : Service() {

    // Reutiliza um único engine no processo filho (sessão ORT persistente — o modelo de
    // ~114MB NÃO é recarregado entre chunks). allowIsolatedProcessDelegation=false evita
    // recursão (o serviço JÁ é o processo isolado).
    private val engine by lazy {
        KokoroTtsEngine(applicationContext, allowIsolatedProcessDelegation = false)
    }

    // Primeiro plano do processo (rb-36). Até 2 sínteses concorrentes (SherpaProcessClient usa
    // Semaphore(2)): o motor conta as chamadas em voo e só sai depois da última e da espera.
    private val motor = PrimeiroPlanoDoMotor(this, NOTIF_ID)

    // Fronteira com nativo: exceção ampla intencional (mesmo motivo do KokoroTtsEngine).
    @Suppress("TooGenericExceptionCaught")
    private val binder = object : ISherpaSynthService.Stub() {
        override fun synthesize(text: String, voiceId: String, outPath: String, paraReproducao: Boolean): String {
            motor.comecar(paraReproducao)
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
        const val TAG = "SherpaSynthSvc"
        const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
        const val NOTIF_ID = 2001
    }
}
