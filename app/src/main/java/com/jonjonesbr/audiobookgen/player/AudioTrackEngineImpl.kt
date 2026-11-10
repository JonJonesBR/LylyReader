package com.jonjonesbr.audiobookgen.player

import android.content.Context
import java.io.File

/**
 * Motor da leitura guiada que toca os WAV PCM 16 bits locais por AudioTrack ([TrilhaPcm]). Tudo o que não couber
 * nesse caminho (MP3, WAV acima do teto, falha ao montar a faixa) vai para a [reserva] (MediaPlayerEngineImpl) sem
 * erro para o usuário. Os setters são repassados à reserva, para a troca entre os dois motores ser transparente.
 */
// A interface AudioPlayerEngine tem 16 membros, e cada override aqui é uma linha: passa do limite do detekt.
@Suppress("TooManyFunctions")
class AudioTrackEngineImpl(
    private val context: Context,
    private val reserva: AudioPlayerEngine = MediaPlayerEngineImpl(context)
) : AudioPlayerEngine {

    @Volatile private var trilha: TrilhaPcm? = null
    @Volatile private var usandoReserva = false
    private var speedAtual = 1f
    private var volumeAtual = 1f
    private var timbre = TimbreVoz.PADRAO
    @Volatile private var completionListener: (() -> Unit)? = null
    // Cada play() abre uma geração nova: o fim de um trecho anterior não dispara o listener do atual.
    // Volatile porque é lida no fim do trecho, que pode chegar em outra thread (play e stop são synchronized).
    @Volatile private var geracao = 0

    override val motorEmUso: String
        get() = if (usandoReserva) "reserva" else "audiotrack"

    @Synchronized
    override fun play(audioFile: File, speed: Float) {
        pararTudo()
        geracao++
        val minha = geracao
        speedAtual = speed
        val nova = criarTrilha(audioFile, minha)
        if (nova != null && nova.iniciar(volumeAtual, speed, timbre.fatorDeTom, timbre.agudosDb)) {
            usandoReserva = false
            trilha = nova
        } else {
            nova?.liberar()
            usandoReserva = true
            reserva.play(audioFile, speed)
        }
    }

    override fun pause() {
        if (usandoReserva) reserva.pause() else trilha?.pausar()
    }

    override fun resume() {
        if (usandoReserva) reserva.resume() else trilha?.retomar(speedAtual, timbre.fatorDeTom)
    }

    @Synchronized
    override fun stop() {
        geracao++
        pararTudo()
    }

    override fun seekTo(positionMs: Int) {
        if (usandoReserva) reserva.seekTo(positionMs) else trilha?.buscar(positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        speedAtual = speed
        reserva.setPlaybackSpeed(speed)
        // Só aplica com a faixa tocando; pausada ou terminada, o valor entra no retomar().
        trilha?.takeIf { it.tocando() }?.aplicarParametros(speed, timbre.fatorDeTom)
    }

    override fun setTimbre(timbre: TimbreVoz) {
        this.timbre = timbre
        reserva.setTimbre(timbre)
        trilha?.let { faixa ->
            if (faixa.tocando()) faixa.aplicarParametros(speedAtual, timbre.fatorDeTom)
            faixa.aplicarAgudos(timbre.agudosDb)
        }
    }

    override fun setVolume(volume: Float) {
        volumeAtual = volume
        reserva.setVolume(volume)
        trilha?.setVolume(volume)
    }

    override fun getCurrentPosition(): Int =
        if (usandoReserva) reserva.getCurrentPosition() else trilha?.posicaoMs() ?: 0

    override fun getDuration(): Int =
        if (usandoReserva) reserva.getDuration() else trilha?.duracaoMs ?: 0

    override fun isPlaying(): Boolean =
        if (usandoReserva) reserva.isPlaying() else trilha?.tocando() == true

    override fun setCompletionListener(listener: () -> Unit) {
        completionListener = listener
        reserva.setCompletionListener(listener)
    }

    override fun setErrorListener(listener: (String) -> Unit) {
        // Erro do AudioTrack nunca chega aqui: a faixa cai na reserva. Só o erro da reserva é avisado.
        reserva.setErrorListener(listener)
    }

    @Synchronized
    override fun release() {
        stop()
        reserva.release()
    }

    private fun pararTudo() {
        trilha?.liberar()
        trilha = null
        reserva.stop()
    }

    /** Monta a faixa de um WAV PCM 16 bits dentro do teto. Devolve null quando o trecho deve ir para a reserva. */
    private fun criarTrilha(arquivo: File, minha: Int): TrilhaPcm? {
        val pcm = avaliarWavPcm16(cabecalhoDoArquivo(arquivo)) ?: return null
        return lerPcm(arquivo, pcm)?.let { dados ->
            TrilhaPcm(context.applicationContext, pcm, dados) {
                // Fim de um trecho que já foi substituído (stop ou novo play) não avisa ninguém.
                if (minha == geracao) completionListener?.invoke()
            }
        }
    }
}
