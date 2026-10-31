package com.jonjonesbr.audiobookgen.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.jonjonesbr.audiobookgen.util.CrashLogWriter

/**
 * Encapsula toda a lógica de foco de áudio do Android.
 *
 * Os callbacks permitem que o dono (AudioPlayerService, GuidedReadingService) reaja aos eventos de
 * foco — pausar/retomar o player, atualizar notificação, etc. — sem
 * que esta classe precise conhecê-los directamente.
 *
 * [tipoDeConteudo]: CONTENT_TYPE_SPEECH para a narração da leitura guiada (o sistema trata fala de outro jeito
 * ao misturar com outros sons); o audiobook segue como música, como antes.
 */
class AudioFocusController(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusLostTransient: () -> Unit,
    private val onFocusGained: () -> Unit,
    private val onDuck: () -> Unit,
    private val tipoDeConteudo: Int = AudioAttributes.CONTENT_TYPE_MUSIC,
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

    /** Pede o foco e devolve o resultado (GRANTED, DELAYED ou FAILED; FAILED também se o pedido lançar erro). */
    fun solicitar(): Int {
        return try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(tipoDeConteudo)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro ao solicitar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.solicitar")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao solicitar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.solicitar")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    fun abandonar() {
        try {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro ao abandonar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.abandonar")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Erro ao abandonar foco de áudio", e)
            CrashLogWriter.log(e, "AudioFocusController.abandonar")
        }
    }
}
