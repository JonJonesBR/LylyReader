package com.jonjonesbr.audiobookgen.tts

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Motor Paradee (espeak-ng + ONNX Runtime) hospedado no processo `:paradee`, fora da UI e do player. */
class ParadeeSynthService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trava = Any()
    private val activeCalls = AtomicInteger(0)

    // Uma sessão ONNX por voz, em ordem de uso (LRU): passando de MAX_SESSOES, a menos usada é fechada.
    private val sessoes = LinkedHashMap<String, Long>(CAPACIDADE_INICIAL, FATOR_DE_CARGA, true)
    private var idleRelease: Runnable? = null

    // Primeiro plano do processo (rb-36): entra na primeira síntese e sai 60 s depois da última.
    private val motor = PrimeiroPlanoDoMotor(this, NOTIFICATION_ID)

    private val binder = object : IParadeeSynthService.Stub() {
        @Suppress("TooGenericExceptionCaught")
        override fun synthesize(
            text: String,
            voiceId: String,
            outPath: String,
            speed: Float,
            paraReproducao: Boolean
        ): String {
            activeCalls.incrementAndGet()
            motor.comecar(paraReproducao)
            return try {
                synchronized(trava) { sintetizarNoMotor(text, voiceId, outPath, speed) }
                ""
            } catch (error: ErroDeSintese) {
                error.message.orEmpty()
            } catch (error: Throwable) {
                Log.e(TAG, "Paradee synthesis failed (voice=$voiceId): ${error.message}", error)
                error.message ?: error.javaClass.simpleName
            } finally {
                motor.terminar()
                if (activeCalls.decrementAndGet() == 0) {
                    synchronized(trava) {
                        if (sessoes.isNotEmpty()) scheduleIdleRelease()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Android 15+: limite de tempo do dataSync atingido. Sai do primeiro plano sem parar o serviço.
    override fun onTimeout(startId: Int, fgsType: Int) {
        motor.aoTimeout()
    }

    override fun onDestroy() {
        motor.encerrar()
        idleRelease?.let(mainHandler::removeCallbacks)
        synchronized(trava) {
            sessoes.values.forEach { NativeParadee.fecharSessao(it) }
            sessoes.clear()
            idleRelease = null
        }
        super.onDestroy()
    }

    /** Sintetiza no motor (chamar com [trava]). Erros esperados saem como [ErroDeSintese]. */
    private fun sintetizarNoMotor(text: String, voiceId: String, outPath: String, speed: Float) {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = null
        val spec = ParadeeVozes.spec(voiceId) ?: throw ErroDeSintese("voz Paradee não reconhecida: $voiceId")
        if (!ParadeeModelManager.isReady(this)) throw ErroDeSintese("pacote Paradee não está instalado")
        iniciarEspeak(spec)

        val velocidade = speed.coerceIn(VELOCIDADE_MIN, VELOCIDADE_MAX)
        // Blocos de até 250 tokens: o pico de memória cresce ~1 MB por token (510 tokens ≈ 600 MB no Redmi).
        val blocos = ParadeeTokens.blocos(text, { trecho -> fonemizar(trecho) }, LIMITE_TOKENS_POR_BLOCO)
        val output = File(outPath)
        output.parentFile?.mkdirs()
        if (blocos.isEmpty()) {
            // Nada falável no trecho: um silêncio curto no lugar do áudio.
            output.writeBytes(SilentWav.bytes(SILENCIO_MS))
        } else {
            escreverSintese(spec, blocos, velocidade, output)
        }
        conferirSaida(output)
    }

    private fun iniciarEspeak(spec: ParadeeVozes.Spec) {
        val dadosEspeak = EspeakDados.garantir(this)
        val erro = NativeParadee.espeakIniciar(dadosEspeak.absolutePath, spec.vozEspeak)
        if (erro.isNotEmpty()) throw ErroDeSintese(erro)
    }

    private fun conferirSaida(output: File) {
        if (!output.isFile || output.length() < MIN_AUDIO_BYTES) {
            output.delete()
            throw ErroDeSintese("WAV Paradee inválido (size=${output.length()})")
        }
    }

    private fun escreverSintese(
        spec: ParadeeVozes.Spec,
        blocos: List<Bloco>,
        velocidade: Float,
        output: File
    ) {
        val handle = sessoes[spec.voiceId] ?: criarSessao(spec)
        val audios = blocos.map { bloco ->
            NativeParadee.sintetizar(handle, bloco.ids, velocidade, spec.indiceVoz)
                ?: throw ErroDeSintese("o Paradee não gerou áudio válido")
        }
        val juntado = ParadeeTokens.juntarComPausa(
            audios,
            blocos.map { it.pausaDepoisMs },
            ParadeeVozes.SAMPLE_RATE
        )
        escreverWav16BitMono(juntado, ParadeeVozes.SAMPLE_RATE, output)
    }

    /** Fonemiza um trecho com o espeak nativo (já inicializado) e devolve o IPA. Em debug, registra no log. */
    private fun fonemizar(trecho: String): String {
        val ipa = NativeParadee.espeakFonemas(trecho.toByteArray(Charsets.UTF_8))
            ?.toString(Charsets.UTF_8).orEmpty()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG_G2P, ipa)
        return ipa
    }

    private fun criarSessao(spec: ParadeeVozes.Spec): Long {
        val modelo = ParadeeModelManager.arquivo(this, spec)
        val handle = NativeParadee.criarSessao(modelo.absolutePath, THREADS)
        if (handle == 0L) throw ErroDeSintese("não foi possível criar a sessão do modelo Paradee")
        sessoes[spec.voiceId] = handle
        while (sessoes.size > MAX_SESSOES) {
            val maisAntiga = sessoes.keys.first()
            NativeParadee.fecharSessao(sessoes.remove(maisAntiga) ?: 0L)
        }
        return handle
    }

    private fun scheduleIdleRelease() {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = Runnable {
            // Roda na main thread do serviço: com síntese em andamento, não espera a trava (risco de ANR)
            // nem fecha as sessões em uso; o fim dessa síntese reagenda a liberação.
            if (activeCalls.get() > 0) {
                idleRelease = null
                return@Runnable
            }
            synchronized(trava) {
                sessoes.values.forEach { NativeParadee.fecharSessao(it) }
                sessoes.clear()
                idleRelease = null
                Log.i(TAG, "Sessões Paradee ociosas liberadas para devolver memória")
            }
        }.also { mainHandler.postDelayed(it, IDLE_RELEASE_DELAY_MS) }
    }

    private companion object {
        const val TAG = "ParadeeSynthService"
        const val TAG_G2P = "ParadeeG2P"
        const val NOTIFICATION_ID = 2004
        const val MIN_AUDIO_BYTES = 500L
        const val LIMITE_TOKENS_POR_BLOCO = 250
        const val MAX_SESSOES = 2
        const val CAPACIDADE_INICIAL = 16
        const val FATOR_DE_CARGA = 0.75f
        const val THREADS = 2
        const val SILENCIO_MS = 400
        const val VELOCIDADE_MIN = 0.5f
        const val VELOCIDADE_MAX = 2f
        const val IDLE_RELEASE_DELAY_MS = 5 * 60_000L
    }
}

/** Erro esperado da síntese: a mensagem vai ao cliente como texto de erro. */
private class ErroDeSintese(mensagem: String) : Exception(mensagem)
