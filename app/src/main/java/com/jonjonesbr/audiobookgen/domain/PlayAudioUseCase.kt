package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
data class PlayerState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPosition: Int = 0,
    val duration: Int = 0,
    val trackName: String = "",
    val speed: Float = 1.0f,
    val caminho: String = "",
    /** Se o audiolivro atual tem marcas de capítulo (T4.4) — controla o modo dos botões recuar/avançar. */
    val temCapitulos: Boolean = false,
    /** Capítulos do audiobook atual (timestamps absolutos no MP3); vazio quando não há marcas.
     *  Alimenta o sumário de capítulos do mini-player. */
    val capitulos: List<CapituloAudio> = emptyList(),
    /** Mensagem de erro amigável do playback atual (ex.: arquivo movido/corrompido); null quando OK.
     *  Exibida na notificação de mídia e no mini-player; o botão de play vira "tentar de novo". */
    val erroMensagem: String? = null
)

/**
 * Prévia de voz (amostra curta ao escolher a voz). O audiobook em si toca no AudioPlayerService; o player antigo que
 * morava aqui (iniciar, play/pausa, seek, velocidade) não tinha mais quem o chamasse e saiu.
 *
 * Chame [release] em onCleared().
 */
@Suppress("UnusedPrivateProperty")
class PlayAudioUseCase(private val context: Context) {

    private val TAG = "PlayAudioUseCase"

    private var previewPlayer: MediaPlayer? = null

    fun iniciarPreview(caminho: String) {
        try {
            previewPlayer?.release()
            previewPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(caminho)
                    prepare()
                    setOnCompletionListener {
                        previewPlayer?.release()
                        previewPlayer = null
                    }
                    setOnErrorListener { _, _, _ ->
                        previewPlayer?.release()
                        previewPlayer = null
                        true
                    }
                    start()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tocar preview: ${e.message}", e)
            previewPlayer?.release()
            previewPlayer = null
        }
    }

    fun pararPreview() {
        try {
            previewPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) { Log.w(TAG, "Falha ao parar preview: ${e.message}") }
        previewPlayer = null
    }

    /** Deve ser chamado em ViewModel.onCleared() para liberar recursos. */
    fun release() {
        pararPreview()
    }
}
