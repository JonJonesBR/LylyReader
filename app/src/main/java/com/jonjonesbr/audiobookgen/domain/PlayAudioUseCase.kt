package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import com.jonjonesbr.audiobookgen.ui.PlayerWidget
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * UseCase responsável por todo o ciclo de vida do MediaPlayer.
 * Expõe [state] como StateFlow para que a ViewModel mescle os dados na UI sem
 * conhecer detalhes de AudioAttributes, PlaybackParams ou seek jobs.
 *
 * Possui escopo coroutine próprio — chame [release] em onCleared().
 */
class PlayAudioUseCase(private val context: Context) {

    companion object {
        private const val TICK_INTERVAL_MS = 500L
    }

    private val TAG = "PlayAudioUseCase"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var previewPlayer: MediaPlayer? = null
    private var tickJob: Job? = null
    private var arrastandoSeek = false

    // ── Player Principal ──────────────────────────────────────────────────────

    fun iniciar(caminho: String, nome: String) {
        parar()
        try {
            mediaPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    val uri = Uri.parse(caminho)
                    if (uri.scheme == "content" || uri.scheme == "file") {
                        setDataSource(context, uri)
                    } else {
                        setDataSource(caminho)
                    }
                    prepare()
                    setOnCompletionListener {
                        _state.update { it.copy(isPlaying = false, currentPosition = 0) }
                        PlayerWidget.atualizarWidget(context)
                    }
                    setOnErrorListener { _, _, _ ->
                        Log.e(TAG, "Erro no MediaPlayer")
                        parar()
                        true
                    }
                }

            val newState = _state.value.copy(
                isVisible = true,
                duration = mediaPlayer!!.duration,
                trackName = nome,
                currentPosition = 0,
                isPlaying = true
            )
            _state.value = newState
            PlayerStateHolder.update(newState)

            mediaPlayer!!.start()
            mediaPlayer!!.playbackParams = PlaybackParams().setSpeed(_state.value.speed)
            iniciarTick()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar player: ${e.message}", e)
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun alternarPlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.update { it.copy(isPlaying = false) }
        } else {
            mp.start()
            try {
                mp.playbackParams = PlaybackParams().setSpeed(_state.value.speed)
            } catch (e: Exception) { Log.w(TAG, "Falha ao aplicar velocidade ao retomar play: ${e.message}") }
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun parar() {
        tickJob?.cancel()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) { Log.w(TAG, "Falha ao parar MediaPlayer: ${e.message}") }
            it.release()
        }
        mediaPlayer = null
        _state.update {
            it.copy(
                isVisible = false,
                isPlaying = false,
                trackName = "",
                currentPosition = 0,
                duration = 0
            )
        }
    }

    fun seek(posicao: Int, isArrastando: Boolean) {
        arrastandoSeek = isArrastando
        if (!isArrastando) mediaPlayer?.seekTo(posicao)
        _state.update { it.copy(currentPosition = posicao) }
    }

    fun seekRelativo(ms: Int) {
        mediaPlayer?.let { mp ->
            val novaPosicao = (mp.currentPosition + ms).coerceIn(0, mp.duration)
            mp.seekTo(novaPosicao)
            _state.update { it.copy(currentPosition = novaPosicao) }
        }
    }

    fun aplicarVelocidade(velocidade: Float) {
        _state.update { it.copy(speed = velocidade) }
        try {
            mediaPlayer?.playbackParams = PlaybackParams().setSpeed(velocidade)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao definir velocidade: ${e.message}")
        }
    }

    // ── Player de Preview ─────────────────────────────────────────────────────

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

    // ── Ciclo de Vida ─────────────────────────────────────────────────────────

    /** Deve ser chamado em ViewModel.onCleared() para liberar recursos. */
    fun release() {
        parar()
        pararPreview()
        scope.cancel()
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private fun iniciarTick() {
        tickJob?.cancel()
        tickJob =
            scope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    val mp = mediaPlayer ?: break
                    // Se o MediaPlayer parou inesperadamente, interrompe o tick e limpa o estado
                    if (!mp.isPlaying && _state.value.isPlaying) {
                        Log.w(TAG, "MediaPlayer parou inesperadamente. Limpando estado...")
                        _state.update { it.copy(isPlaying = false) }
                        PlayerStateHolder.update(_state.value)
                        tickJob?.cancel()
                        break
                    }
                    if (!arrastandoSeek) {
                        val updated = _state.value.copy(currentPosition = mp.currentPosition)
                        _state.value = updated
                        PlayerStateHolder.update(updated)
                    }
                }
            }
    }
}
