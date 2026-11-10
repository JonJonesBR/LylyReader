package com.jonjonesbr.audiobookgen.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uma unidade da leitura guiada tocada por AudioTrack em MODE_STATIC. É dona da faixa, do equalizador de agudos e
 * do WakeLock. Posição e duração ficam na mesma base (ms do arquivo de origem). O fim sai uma vez só, pelo marcador
 * de fim ou pelo vigia, e sempre na thread principal.
 */
// Motor pequeno e coeso: cada função é uma ação da faixa (tocar, pausar, buscar, fim, liberar).
@Suppress("TooManyFunctions")
internal class TrilhaPcm(
    private val context: Context,
    private val pcm: PcmWav,
    private val dados: ByteArray,
    private val aoTerminar: () -> Unit
) {
    private val totalFrames = dados.size / (pcm.canais * BYTES_POR_AMOSTRA_16)
    private val mascaraCanais =
        if (pcm.canais == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    /** Duração do trecho em ms do arquivo de origem (a mesma base da posição). */
    val duracaoMs: Int = framesParaMs(totalFrames, pcm.taxa)

    private var faixa: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null
    private val agudos = AgudosNoEqualizador()
    private val handler = Handler(Looper.getMainLooper())
    // Garante um único fim por trecho; liberar() também o consome, para um fim em fila morrer no if.
    private val fimAvisado = AtomicBoolean(false)
    @Volatile private var terminada = false
    @Volatile private var liberada = false
    private var paramsAplicados = false
    private var velocidade = 1f
    private var tentativasVigia = 0

    private val marcador = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack?) = terminar()
        override fun onPeriodicNotification(track: AudioTrack?) = Unit
    }

    // Rede de segurança para aparelhos que não avisam o marcador quando a posição para exatamente no fim.
    private val vigia = Runnable { conferirFim() }

    /** Cria a faixa e começa a tocar. Devolve false se qualquer etapa falhar; nesse caso tudo já foi liberado. */
    fun iniciar(volume: Float, speed: Float, tom: Float, agudosDb: Int): Boolean = runCatching {
        val nova = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.taxa)
                    .setChannelMask(mascaraCanais)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(dados.size)
            .build()
        faixa = nova
        check(nova.write(dados, 0, dados.size) == dados.size) { "AudioTrack não aceitou todo o áudio" }
        nova.setVolume(volume.coerceIn(0f, 1f))
        velocidade = velocidadeValida(speed)
        aplicarPlaybackParams(nova, velocidade, tom)
        // O equalizador é preso à sessão antes do play(): depois, dava um estalo no começo (como no MediaPlayer).
        agudos.aplicar(nova.audioSessionId, agudosDb)
        nova.setNotificationMarkerPosition(totalFrames)
        nova.setPlaybackPositionUpdateListener(marcador, handler)
        adquirirWake()
        nova.play()
        agendarVigia()
        true
    }.getOrElse {
        Log.e(TAG, "AudioTrack não tocou; o trecho vai para o MediaPlayer", it)
        liberar()
        false
    }

    /** Pausa a faixa e solta o WakeLock. Sem efeito antes do play() ou depois do fim. */
    fun pausar() {
        handler.removeCallbacks(vigia)
        runCatching { faixa?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause() }
        soltarWake()
    }

    /** Retoma do ponto em que parou. Não faz nada depois do fim: o trecho acabou, como no MediaPlayer. */
    fun retomar(speed: Float, tom: Float) {
        val f = faixa ?: return
        if (liberada || terminada) return
        velocidade = velocidadeValida(speed)
        runCatching {
            aplicarPlaybackParams(f, velocidade, tom)
            adquirirWake()
            f.play()
            agendarVigia()
        }.onFailure { Log.w(TAG, "Falha ao retomar o trecho", it) }
    }

    /** Muda velocidade e tom; a 1,0 / 1,0 volta a faixa ao estado sem esticamento, uma única vez. */
    fun aplicarParametros(speed: Float, tom: Float) {
        velocidade = velocidadeValida(speed)
        val f = faixa ?: return
        runCatching { aplicarPlaybackParams(f, velocidade, tom) }
            .onFailure { Log.w(TAG, "Falha ao mudar velocidade ou tom do trecho", it) }
        if (tocando()) agendarVigia()
    }

    fun setVolume(volume: Float) {
        runCatching { faixa?.setVolume(volume.coerceIn(0f, 1f)) }
    }

    fun aplicarAgudos(agudosDb: Int) {
        faixa?.let { agudos.aplicar(it.audioSessionId, agudosDb) }
    }

    /** Posição em ms do arquivo de origem, limitada à duração. Depois do fim, devolve a duração. */
    fun posicaoMs(): Int {
        val f = faixa
        if (terminada) return duracaoMs
        val frames = if (f == null) 0 else runCatching { f.playbackHeadPosition }.getOrDefault(0)
        return framesParaMs(frames, pcm.taxa).coerceIn(0, duracaoMs)
    }

    fun tocando(): Boolean {
        if (liberada || terminada) return false
        return runCatching { faixa?.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)
    }

    fun terminou(): Boolean = terminada

    /** Busca a posição (ms). Com a faixa tocando, pausa, move a cabeça e volta a tocar. */
    fun buscar(ms: Int) {
        val f = faixa ?: return
        if (liberada || terminada) return
        val estavaTocando = tocando()
        runCatching {
            if (estavaTocando) f.pause()
            f.setPlaybackHeadPosition(framesDeMs(ms))
            if (estavaTocando) f.play()
        }.onFailure { Log.w(TAG, "Falha ao buscar a posição do trecho", it) }
        if (estavaTocando) agendarVigia()
    }

    /** Libera a faixa, o equalizador e o WakeLock. Pode ser chamada mais de uma vez. */
    fun liberar() {
        if (liberada) return
        liberada = true
        fimAvisado.set(true)
        handler.removeCallbacks(vigia)
        val f = faixa
        faixa = null
        runCatching { f?.setPlaybackPositionUpdateListener(null) }
        soltarWake()
        agudos.liberar()
        // Sem stop(): só pausar e liberar, que não lança IllegalStateException em estado inesperado.
        runCatching { f?.pause() }
        runCatching { f?.release() }
    }

    private fun aplicarPlaybackParams(f: AudioTrack, speed: Float, tom: Float) {
        if (precisaDeParametros(speed, tom)) {
            f.setPlaybackParams(PlaybackParams().setSpeed(speed).setPitch(tom))
            paramsAplicados = true
        } else if (paramsAplicados) {
            f.setPlaybackParams(PlaybackParams().setSpeed(1f).setPitch(1f))
            paramsAplicados = false
        }
    }

    private fun terminar() {
        if (!fimAvisado.compareAndSet(false, true)) return
        handler.removeCallbacks(vigia)
        soltarWake()
        terminada = true
        aoTerminar()
    }

    private fun conferirFim() {
        if (terminada || liberada) return
        when {
            posicaoMs() >= duracaoMs - MARGEM_FIM_MS -> terminar()
            tocando() && tentativasVigia < MAX_TENTATIVAS_VIGIA -> {
                tentativasVigia++
                handler.postDelayed(vigia, INTERVALO_VIGIA_MS)
            }
        }
    }

    private fun agendarVigia() {
        tentativasVigia = 0
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, esperaDoVigia())
    }

    /** Tempo de parede até o fim do trecho (o restante na velocidade atual) mais uma margem. */
    private fun esperaDoVigia(): Long = (restanteMs() / velocidade).toLong() + MARGEM_VIGIA_MS

    private fun restanteMs(): Int = (duracaoMs - posicaoMs()).coerceAtLeast(0)

    private fun framesDeMs(ms: Int): Int =
        (ms.toLong() * pcm.taxa / MS_POR_SEGUNDO).toInt().coerceIn(0, totalFrames)

    private fun adquirirWake() {
        val trava = wake ?: criarWake()?.also { wake = it } ?: return
        trava.acquire((restanteMs() / velocidade).toLong() + TIMEOUT_WAKE_MS)
    }

    private fun criarWake(): PowerManager.WakeLock? =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKE)
            ?.apply { setReferenceCounted(false) }

    private fun soltarWake() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
    }

    private companion object {
        const val TAG = "TrilhaPcm"
        const val TAG_WAKE = "LylyReader:TrilhaPcm"
        const val BYTES_POR_AMOSTRA_16 = 2
        const val MS_POR_SEGUNDO = 1000L
        const val MARGEM_VIGIA_MS = 600L
        const val MARGEM_FIM_MS = 150
        const val INTERVALO_VIGIA_MS = 200L
        const val MAX_TENTATIVAS_VIGIA = 2
        const val TIMEOUT_WAKE_MS = 15_000L
    }
}

/** Velocidade nunca vai a zero: um valor inválido vira a velocidade normal. */
private fun velocidadeValida(speed: Float): Float = if (speed > 0f) speed else 1f
