package com.jonjonesbr.audiobookgen.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@OptIn(UnstableApi::class)
class ExoPlayerEngineImpl(private val context: Context) : AudioPlayerEngine {

    private var exoPlayer: ExoPlayer? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var currentSpeed: Float = 1.0f
    private var timbre: TimbreVoz = TimbreVoz.PADRAO
    private var equalizador: android.media.audiofx.Equalizer? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onCompletionListener?.invoke()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val errorMsg = "ExoPlayer error: ${error.message}"
            Log.e("ExoPlayerEngine", errorMsg, error)
            onErrorListener?.invoke(errorMsg)
        }
    }

    override fun play(audioFile: File, speed: Float) {
        try {
            stop()
            currentSpeed = speed

            val player = ExoPlayer.Builder(context).build().apply {
                addListener(playerListener)
                val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
                setMediaItem(mediaItem)
                playbackParameters = PlaybackParameters(currentSpeed, timbre.fatorDeTom)
                prepare()
            }

            exoPlayer = player
            player.play()
            aplicarAgudos(player.audioSessionId)
        } catch (e: Exception) {
            val errorMsg = "Error starting ExoPlayer: ${e.message}"
            Log.e("ExoPlayerEngine", errorMsg, e)
            onErrorListener?.invoke(errorMsg)
        }
    }

    override fun pause() {
        try {
            exoPlayer?.pause()
        } catch (e: IllegalStateException) {
            Log.e("ExoPlayerEngine", "Error pausing", e)
        }
    }

    override fun resume() {
        try {
            exoPlayer?.play()
            // Re-aplicar velocidade
            setPlaybackSpeed(currentSpeed)
        } catch (e: Exception) {
            Log.e("ExoPlayerEngine", "Error resuming", e)
        }
    }

    override fun setTimbre(timbre: TimbreVoz) {
        this.timbre = timbre
        exoPlayer?.let {
            setPlaybackSpeed(currentSpeed)
            aplicarAgudos(it.audioSessionId)
        }
    }

    /** Corte suave de agudos com o equalizador do sistema (bandas acima de ~2,5 kHz). */
    private fun aplicarAgudos(sessao: Int) {
        try {
            equalizador?.release()
            equalizador = null
            if (timbre.agudosDb <= 0 || sessao == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) return
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
            Log.w("ExoPlayerEngine", "Equalizador indisponível: ${e.message}")
        }
    }

    override fun stop() {
        try {
            equalizador?.release()
            equalizador = null
            exoPlayer?.let {
                it.removeListener(playerListener)
                it.stop()
                it.release()
            }
            exoPlayer = null
        } catch (e: Exception) {
            Log.e("ExoPlayerEngine", "Error stopping", e)
        }
    }

    override fun seekTo(positionMs: Int) {
        try {
            exoPlayer?.seekTo(positionMs.toLong())
        } catch (e: IllegalStateException) {
            Log.e("ExoPlayerEngine", "Error seeking", e)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed, timbre.fatorDeTom)
        } catch (e: Exception) {
            Log.e("ExoPlayerEngine", "Error setting speed", e)
        }
    }

    override fun setVolume(volume: Float) {
        try {
            exoPlayer?.volume = volume
        } catch (e: IllegalStateException) {
            Log.e("ExoPlayerEngine", "Error setting volume", e)
        }
    }

    override fun getCurrentPosition(): Int {
        return try {
            exoPlayer?.currentPosition?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun getDuration(): Int {
        return try {
            val duration = exoPlayer?.duration ?: 0
            if (duration == androidx.media3.common.C.TIME_UNSET) 0 else duration.toInt()
        } catch (e: Exception) {
            0
        }
    }

    override fun isPlaying(): Boolean {
        return try {
            exoPlayer?.isPlaying ?: false
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
