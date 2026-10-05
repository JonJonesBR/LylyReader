package com.jonjonesbr.audiobookgen.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.jonjonesbr.audiobookgen.util.CrashLogWriter

/**
 * Encapsula toda a lógica de foco de áudio do Android.
 *
 * Os callbacks permitem que o dono (AudioPlayerService) reaja aos eventos de
 * foco — pausar/retomar o MediaPlayer, atualizar notificação, etc. — sem
 * que esta classe precise conhecê-los directamente.
 */
class AudioFocusController(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusLostTransient: () -> Unit,
    private val onFocusGained: () -> Unit,
    private val onDuck: () -> Unit,
) {

    companion object {
        private const val TAG = "AudioFocusController"
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Perda permanente (outro app tomou o foco definitivamente)
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Perda temporária (ex: GPS, notificação curta)
                onFocusLostTransient()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Pode reduzir volume (ex: notificação sonora breve)
                onDuck()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Foco recuperado — restaura volume e retoma se estava tocando
                onFocusGained()
            }
        }
    }

    fun solicitar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro ao solicitar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.solicitar")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao solicitar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.solicitar")
        }
    }

    fun abandonar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro ao abandonar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.abandonar")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao abandonar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.abandonar")
        }
    }
}
