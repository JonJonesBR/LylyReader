package com.jonjonesbr.audiobookgen.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import java.io.File

class MediaPlayerEngineImpl(private val context: Context) : AudioPlayerEngine {

    private var mediaPlayer: MediaPlayer? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var currentSpeed: Float = 1.0f
    // Último volume pedido (fade da soneca): cada trecho cria um MediaPlayer novo, que nasceria em 1.0.
    private var volumeAtual: Float = 1.0f
    private var timbre: TimbreVoz = TimbreVoz.PADRAO
    private val agudos = AgudosNoEqualizador()

    override fun play(audioFile: File, speed: Float) {
        try {
            stop()
            currentSpeed = speed

            val player = MediaPlayer().apply {
                // Mantém o CPU ativo com a tela apagada para a leitura guiada não parar (#4)
                setWakeMode(context.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    onCompletionListener?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    val errorMsg = "MediaPlayer error: what=$what, extra=$extra"
                    Log.e("MediaPlayerEngine", errorMsg)
                    onErrorListener?.invoke(errorMsg)
                    true
                }
                prepare()
                setVolume(volumeAtual, volumeAtual)
            }

            mediaPlayer = player
            // Equalizador antes do som: preso à sessão com o áudio já tocando, dava um estalo no começo.
            agudos.aplicar(player.audioSessionId, timbre.agudosDb)
            // Depois do prepare(), PlaybackParams com velocidade > 0 já inicia a reprodução (= start()).
            aplicarParametros(player)
            if (!player.isPlaying) player.start()
        } catch (e: Exception) {
            val errorMsg = "Error starting playback: ${e.message}"
            Log.e("MediaPlayerEngine", errorMsg, e)
            onErrorListener?.invoke(errorMsg)
        }
    }

    override fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: IllegalStateException) {
            Log.e("MediaPlayerEngine", "Error pausing", e)
        }
    }

    override fun resume() {
        try {
            mediaPlayer?.let { player ->
                // Velocidade e tom guardados durante a pausa entram junto com o retorno.
                aplicarParametros(player)
                if (!player.isPlaying) player.start()
            }
        } catch (e: Exception) {
            Log.e("MediaPlayerEngine", "Error resuming", e)
        }
    }

    override fun setTimbre(timbre: TimbreVoz) {
        this.timbre = timbre
        mediaPlayer?.let { player ->
            if (tocando(player)) aplicarParametros(player)
            agudos.aplicar(player.audioSessionId, timbre.agudosDb)
        }
    }

    override fun stop() {
        try {
            agudos.liberar()
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: IllegalStateException) {
            Log.e("MediaPlayerEngine", "Error stopping", e)
        }
    }

    override fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (e: IllegalStateException) {
            Log.e("MediaPlayerEngine", "Error seeking", e)
        }
    }

    /**
     * Só aplica no player que está tocando. Num player pausado ou que já terminou, PlaybackParams com
     * velocidade > 0 equivale a start(): o trecho anterior voltava a tocar do começo por um instante antes
     * do próximo (eco no início da frase ao trocar o tom de uma fala) e a pausa era desfeita. Parado, o
     * valor fica guardado e entra no próximo play()/resume().
     */
    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        mediaPlayer?.let { player -> if (tocando(player)) aplicarParametros(player) }
    }

    private fun tocando(player: MediaPlayer): Boolean =
        try { player.isPlaying } catch (e: IllegalStateException) { false }

    private fun aplicarParametros(player: MediaPlayer) {
        try {
            player.playbackParams = PlaybackParams().apply {
                this.speed = currentSpeed
                this.pitch = timbre.fatorDeTom
            }
        } catch (e: Exception) {
            Log.e("MediaPlayerEngine", "Error setting playback speed", e)
        }
    }

    override fun setVolume(volume: Float) {
        volumeAtual = volume
        try {
            mediaPlayer?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
            Log.e("MediaPlayerEngine", "Error setting volume", e)
        }
    }

    override fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun setCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    override fun setErrorListener(listener: (String) -> Unit) {
        onErrorListener = listener
    }

    override fun release() {
        stop()
    }
}
