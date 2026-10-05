package com.jonjonesbr.audiobookgen.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import java.io.File

class MediaPlayerEngineImpl(private val context: Context) : AudioPlayerEngine {

    private var mediaPlayer: MediaPlayer? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var currentSpeed: Float = 1.0f
    private var timbre: TimbreVoz = TimbreVoz.PADRAO
    private var equalizador: android.media.audiofx.Equalizer? = null

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
            }

            mediaPlayer = player
            setPlaybackSpeed(currentSpeed)
            aplicarAgudos(player.audioSessionId)
            player.start()
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
            mediaPlayer?.start()
            // Re-aplicar velocidade caso o MediaPlayer reinicie o tom
            setPlaybackSpeed(currentSpeed)
        } catch (e: Exception) {
            Log.e("MediaPlayerEngine", "Error resuming", e)
        }
    }

    override fun setTimbre(timbre: TimbreVoz) {
        this.timbre = timbre
        if (mediaPlayer != null) {
            setPlaybackSpeed(currentSpeed)
            mediaPlayer?.let { aplicarAgudos(it.audioSessionId) }
        }
    }

    /** Corte suave de agudos com o equalizador do sistema (bandas acima de ~2,5 kHz). */
    private fun aplicarAgudos(sessao: Int) {
        try {
            equalizador?.release()
            equalizador = null
            if (timbre.agudosDb <= 0) return
            val eq = android.media.audiofx.Equalizer(0, sessao)
            val faixa = eq.bandLevelRange
            for (banda in 0 until eq.numberOfBands) {
                val centroHz = eq.getCenterFreq(banda.toShort()) / 1000
                val corteDb = when {
                    centroHz >= 8000 -> timbre.agudosDb.toDouble()
                    centroHz >= 2500 -> timbre.agudosDb / 2.0
                    else -> 0.0
                }
                if (corteDb > 0) {
                    eq.setBandLevel(banda.toShort(), (-corteDb * 100).toInt().coerceIn(faixa[0].toInt(), faixa[1].toInt()).toShort())
                }
            }
            eq.enabled = true
            equalizador = eq
        } catch (e: Exception) {
            Log.w("MediaPlayerEngine", "Equalizador indisponível: ${e.message}")
        }
    }

    override fun stop() {
        try {
            equalizador?.release()
            equalizador = null
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

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        try {
            mediaPlayer?.let { player ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = PlaybackParams().apply {
                        this.speed = speed
                        this.pitch = timbre.fatorDeTom
                    }
                    player.playbackParams = params
                }
            }
        } catch (e: Exception) {
            Log.e("MediaPlayerEngine", "Error setting playback speed", e)
        }
    }

    override fun setVolume(volume: Float) {
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
