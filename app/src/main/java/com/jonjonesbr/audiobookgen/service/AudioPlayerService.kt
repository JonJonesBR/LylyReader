package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.StatsStore
import com.jonjonesbr.audiobookgen.domain.AudiobookLibraryUseCase
import com.jonjonesbr.audiobookgen.domain.CapituloAudio
import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.ui.AudiobookItem
import com.jonjonesbr.audiobookgen.ui.PlayerWidget
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import com.jonjonesbr.audiobookgen.util.calcularRecuoAutoRewind
import com.jonjonesbr.audiobookgen.util.capituloAnteriorMs
import com.jonjonesbr.audiobookgen.util.proximoCapituloMs
import com.jonjonesbr.audiobookgen.util.proximoPresetSonecaMin
import com.jonjonesbr.audiobookgen.ui.MainActivity
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Player do audiolivro convertido. Desde a Fase 5 (item 3, Android Auto) é um
 * [MediaLibraryService] (media3) — expõe a biblioteca de audiobooks pro carro e usa
 * [ExoPlayer] (antes era [android.media.MediaPlayer] cru). Toda a lógica de domínio
 * (auto-rewind, fade do sleep timer, duck de foco, capítulos, estatísticas, widget)
 * permanece igual — só as chamadas primitivas de player mudaram.
 *
 * O [ExoPlayer] é criado UMA VEZ (não recriado por faixa, diferente do `MediaPlayer` de
 * antes) porque a [MediaSession] media3 fica vinculada a uma única instância de [Player] —
 * trocar de audiobook substitui o item ([iniciar]), não o player. Isso também unifica o
 * caminho de bookkeeping (chapter marks, retomada de progresso, notificação): tanto
 * [iniciar] (chamado pela UI do app) quanto uma seleção feita no Android Auto (que resolve
 * o item em [onAddMediaItems] e deixa o framework aplicá-lo no mesmo player) disparam o
 * mesmo [Player.Listener.onMediaItemTransition].
 */
// Serviço central de playback + biblioteca navegável do Android Auto: a contagem alta de
// funções é a superfície real de um MediaLibraryService (lifecycle, comandos de transporte,
// callbacks de biblioteca, bookkeeping de domínio) — fragmentar mais não ajudaria a leitura.
// Já estava suprimido antes da migração (baseline tinha essa chave p/ "AudioPlayerService : Service";
// mudou de superclasse, a chave não casa mais).
@Suppress("TooManyFunctions")
@OptIn(UnstableApi::class)
class AudioPlayerService : MediaLibraryService() {

    companion object {
        const val ACTION_INICIAR    = "com.jonjonesbr.audiobookgen.INICIAR"
        const val EXTRA_CAMINHO     = "extra_caminho"
        const val EXTRA_NOME        = "extra_nome"
        private const val TAG       = "AudioPlayerService"
        private const val TICK_INTERVAL_MS = 500L
        private const val MS_POR_MINUTO = 60_000L
        private const val MEDIA_ROOT_ID = "root"
        private const val MARGEM_RETOMADA_MS = 3_000
        private const val MARGEM_FIM_RETOMADA_MS = 10_000

        private const val VOLUME_DUCK = 0.2f
        /** Sem marcas de capítulo, recuar/avançar pula 15s (T4.4). */
        private const val PULO_SEM_CAPITULO_MS = 15_000
        private const val INTERVALO_SALVAR_PROGRESSO_MS = 5_000
    }

    inner class AudioBinder : Binder() {
        fun getService(): AudioPlayerService = this@AudioPlayerService
    }

    private val binder = AudioBinder()
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null
    private var fileObserver: FileObserver? = null
    private var arrastandoSeek = false
    private var concluido = false
    private var aguardandoReady = false
    // Caminho do item que de fato passou por prepararNovoItem() — distinto de
    // _state.value.caminho, que iniciar() escreve OTIMISTICAMENTE (feedback imediato de UI)
    // antes do ExoPlayer confirmar a troca via onMediaItemTransition. Ver comentário lá.
    private var caminhoPreparado: String? = null
    private var falhaAntesDoPrimeiroReady = false
    private var ultimoSalvarProgressoMs = 0L
    private var msOuvidoAcumulado = 0L
    private var capitulosAtual: List<CapituloAudio> = emptyList()

    private var estavaTocandoAntesDoFoco = false

    // Fator de fade-out do sleep timer (1f = volume cheio); combinado com o volume de
    // "duck" do foco de áudio para as duas reduções de volume não se sobrescreverem.
    private var fadeVolumeFactor = 1f

    private val appPrefs by lazy { AppPrefs(applicationContext) }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> get() = _state.asStateFlow()

    private val playerNotificationManager by lazy {
        PlayerNotificationManager(
            context = applicationContext,
            obterEstado = { _state.value },
            mediaSession = mediaLibrarySession,
            onStartForeground = { notif ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(PlayerNotificationManager.NOTIF_PLAYER_ID, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(PlayerNotificationManager.NOTIF_PLAYER_ID, notif)
                }
            },
        )
    }

    private val audioFocusController by lazy {
        AudioFocusController(
            context = applicationContext,
            onFocusLost = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    marcarPausa()
                    _state.update { it.copy(isPlaying = false) }
                    PlayerStateHolder.update(_state.value)
                    playerNotificationManager.atualizarNotificacao()
                }
                estavaTocandoAntesDoFoco = false
            },
            onFocusLostTransient = {
                estavaTocandoAntesDoFoco = exoPlayer.isPlaying
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    marcarPausa()
                    _state.update { it.copy(isPlaying = false) }
                    PlayerStateHolder.update(_state.value)
                    playerNotificationManager.atualizarNotificacao()
                }
            },
            onFocusGained = {
                exoPlayer.volume = fadeVolumeFactor
                if (estavaTocandoAntesDoFoco) {
                    if (!exoPlayer.isPlaying) {
                        exoPlayer.play()
                        exoPlayer.playbackParameters = PlaybackParameters(_state.value.speed)
                        aplicarAutoRewindNoResume()
                        _state.update { it.copy(isPlaying = true) }
                        PlayerStateHolder.update(_state.value)
                        playerNotificationManager.atualizarNotificacao()
                    }
                    estavaTocandoAntesDoFoco = false
                }
            },
            onDuck = {
                exoPlayer.volume = VOLUME_DUCK * fadeVolumeFactor
            },
        )
    }

    /**
     * Envolve o ExoPlayer real só pra fazer os comandos de skip (Bluetooth/wearables/Android
     * Auto — "próximo"/"anterior") navegarem por CAPÍTULO em vez do comportamento padrão de
     * troca de item de playlist (não se aplica aqui — só tocamos 1 item por vez). Escopo
     * mínimo deliberado: só sobrescreve o que precisa, delega tudo mais via super (a doc do
     * media3 avisa que [ForwardingPlayer] é fácil de errar se você mexer em mais do que
     * o necessário).
     */
    private inner class ChapterAwarePlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                super.isCommandAvailable(command)

        override fun seekToNext() = proximoCapitulo()
        override fun seekToPrevious() = capituloAnterior()
    }

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(MEDIA_ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != MEDIA_ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val itens = AudiobookLibraryUseCase.listar(applicationContext).map { it.paraMediaItem() }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(itens), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolvidos = mediaItems.map { item -> item.buildUpon().setUri(uriPara(item.mediaId)).build() }
            return Futures.immediateFuture(resolvidos)
        }
    }

    private val notificationProvider = object : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification = MediaNotification(
            PlayerNotificationManager.NOTIF_PLAYER_ID,
            playerNotificationManager.construirNotificacaoAtual()
        )

        override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) = false
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val caminho = mediaItem?.mediaId ?: return
            // Bug real encontrado em device (2026-09-16): comparar contra _state.value.caminho
            // aqui SEMPRE dava match e pulava prepararNovoItem() pra QUALQUER item novo — iniciar()
            // já escreve state.caminho=caminho otimisticamente ANTES de setMediaItem() (feedback
            // imediato de UI), então por essa comparação "o item já era o mesmo" mesmo sendo a
            // primeira vez. Resultado: aguardandoReady nunca virava true, aoFicarPronto() nunca
            // rodava — duração/posição/tick nunca inicializavam (mini-player preso em "0:00/0:00"
            // e barra parada, mesmo tocando de verdade — a notificação media3 não depende disso,
            // por isso só o mini-player era afetado). caminhoPreparado só é escrito por
            // prepararNovoItem(), nunca otimisticamente — reflete de verdade o último item preparado.
            if (caminhoPreparado == caminho) return
            val nome = mediaItem.mediaMetadata.title?.toString() ?: File(caminho).nameWithoutExtension
            prepararNovoItem(caminho, nome)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (aguardandoReady) {
                    aguardandoReady = false
                    aoFicarPronto()
                }
                Player.STATE_ENDED -> aoTerminar()
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Erro no ExoPlayer: ${error.message}", error)
            // Sem isso, um erro antes de STATE_READY deixava aguardandoReady travado em true:
            // aoFicarPronto() nunca rodaria pra este item (sem notificação, sem tick, sem
            // capítulos), mesmo que uma tentativa seguinte tivesse sucesso.
            falhaAntesDoPrimeiroReady = aguardandoReady
            aguardandoReady = false
            try { if (exoPlayer.isPlaying) exoPlayer.pause() } catch (_: Exception) {}
            // Feedback visível (P1): sem isso o erro ia só pro Logcat — notificação e
            // mini-player mostravam "Pausado" normal, e o usuário ficava num loop de tap
            // silencioso (retry abaixo re-tenta, mas sem explicação do motivo).
            _state.update { it.copy(isPlaying = false, erroMensagem = getString(R.string.player_erro_reproducao)) }
            PlayerStateHolder.update(_state.value)
            playerNotificationManager.atualizarNotificacao()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        inicializarPlayer()
        setMediaNotificationProvider(notificationProvider)

        // Sleep timer: atualiza a contagem na notificação e pausa ao terminar.
        serviceScope.launch {
            SleepTimerManager.remainingMs.collect {
                if (_state.value.isVisible) playerNotificationManager.atualizarNotificacao()
            }
        }
        // Fade-out nos últimos segundos da soneca, em vez de corte seco de volume.
        serviceScope.launch {
            SleepTimerManager.volumeFactor.collect { fator ->
                fadeVolumeFactor = fator
                exoPlayer.volume = fator
            }
        }
        serviceScope.launch {
            SleepTimerManager.finished.collect {
                if (_state.value.isPlaying) alternarPlayPause()
            }
        }
    }

    private fun inicializarPlayer() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
            setAudioAttributes(audioAttrs, false)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(playerListener)
        }
        mediaLibrarySession = MediaLibrarySession.Builder(this, ChapterAwarePlayer(exoPlayer), librarySessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaLibrarySession

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent) ?: binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // MediaSessionService.onStartCommand() devolve START_STICKY — se o processo morrer
            // (ex.: gerenciador de bateria do MIUI matando o app "fechado por completo") enquanto
            // este serviço tocava em primeiro plano, o Android tenta reiniciá-lo sozinho com
            // intent=null. Sem esse branch, nenhuma ação do "when" abaixo batia, iniciar() nunca
            // era chamado e o serviço ficava "zumbi": vivo, sem notificação, sem tocar nada —
            // bug real relatado pelo usuário (player some da barra de notificações,
            // principalmente depois de fechar o app por completo e reabrir). Sem estado de
            // playback pra restaurar aqui, para o serviço de forma limpa: a próxima ação real do
            // usuário (play) sobe um serviço novo, correto, com notificação.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_INICIAR    -> {
                val caminho = intent.getStringExtra(EXTRA_CAMINHO) ?: return START_NOT_STICKY
                val nome    = intent.getStringExtra(EXTRA_NOME) ?: File(caminho).nameWithoutExtension
                iniciar(caminho, nome)
            }
            PlayerNotificationManager.ACTION_PLAY_PAUSE -> alternarPlayPause()
            PlayerNotificationManager.ACTION_RECUAR     -> capituloAnterior()
            PlayerNotificationManager.ACTION_AVANCAR    -> proximoCapitulo()
            PlayerNotificationManager.ACTION_SONECA     -> alternarSonecaPreset()
            PlayerNotificationManager.ACTION_FECHAR     -> fecharPlayer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        salvarProgresso()
        if (exoPlayer.isPlaying) marcarPausa()
        tickJob?.cancel()
        fileObserver?.stopWatching()
        mediaLibrarySession.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onDestroy()
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /** Toca [caminho] no player já existente — chamado pela UI do app (mini-player, leitor, etc.). */
    fun iniciar(caminho: String, nome: String) {
        // Garante ciclo de vida "started" para sobreviver ao unbind quando o app é minimizado.
        // Sem isso, o Android mata o serviço ao fazer unbindService() no onStop() da Activity.
        startService(Intent(this, AudioPlayerService::class.java))
        audioFocusController.solicitar()
        // Reflete o item novo na UI/notificação imediatamente (o onMediaItemTransition é
        // async) e limpa o erro de playback do item anterior.
        _state.update { it.copy(trackName = nome, caminho = caminho, erroMensagem = null, isVisible = true) }
        // Replay do MESMO audiobook já concluído: setMediaItem com o mesmo mediaId não dispara
        // onMediaItemTransition, então o reset de prepararNovoItem() nunca roda — salvarProgresso/
        // marcarPausa ficavam desativados (concluido=true) e capítulos/estatísticas não reiniciavam.
        // Compara contra caminhoPreparado (não _state.value.caminho, que a linha acima acabou
        // de sobrescrever para QUALQUER item — usar state aqui faria este branch disparar
        // também pra um livro novo, não só pra replay de verdade).
        if (concluido && caminhoPreparado == caminho) {
            prepararNovoItem(caminho, nome)
        }
        val item = MediaItem.Builder()
            .setUri(uriPara(caminho))
            .setMediaId(caminho)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(nome).setIsBrowsable(false).setIsPlayable(true).build()
            )
            .build()
        // Garante foreground + notificação desde o primeiro momento (inclusive em falha
        // imediata de prepare): sem isso um erro antes do primeiro ready deixava o serviço
        // started sem FGS e a notificação podia nem existir — a falha ficava invisível.
        playerNotificationManager.iniciarForeground()
        try {
            exoPlayer.setMediaItem(item)
            exoPlayer.playbackParameters = PlaybackParameters(_state.value.speed)
            exoPlayer.prepare()
            exoPlayer.play()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar: ${e.message}", e)
            CrashLogWriter.log(e, "AudioPlayerService.iniciar", "caminho=$caminho, nome=$nome")
        }
    }

    /** Após erro no prepare, o player volta pra STATE_IDLE e play() sozinho é no-op — prepara
     *  uma nova tentativa, re-armando o sinal de primeiro ready se o erro veio antes dele
     *  (assim aoFicarPronto() roda no próximo ready: foreground, tick e capítulos voltam). */
    private fun prepararRetomadaAposErro() {
        if (falhaAntesDoPrimeiroReady) {
            aguardandoReady = true
        }
        exoPlayer.prepare()
    }

    fun alternarPlayPause() {
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                _state.update { it.copy(isPlaying = false) }
                salvarProgresso()
                marcarPausa()
            } else {
                // Livro concluído tocado de novo: o player ficou em STATE_ENDED e play() sozinho
                // é no-op — reinicia do começo e re-dispara aoFicarPronto (tick, capítulos,
                // foreground), em vez de deixar o play da notificação morto.
                if (concluido) {
                    concluido = false
                    aguardandoReady = true
                    exoPlayer.seekTo(0)
                } else if (
                    exoPlayer.playbackState == Player.STATE_IDLE ||
                    exoPlayer.playerError != null
                ) {
                    // Após erro (de prepare ou de source), play() é no-op — re-prepara pra dar
                    // uma nova tentativa ao usuário e limpa a mensagem de erro exibida.
                    prepararRetomadaAposErro()
                }
                exoPlayer.play()
                exoPlayer.playbackParameters = PlaybackParameters(_state.value.speed)
                aplicarAutoRewindNoResume()
                _state.update { it.copy(isPlaying = true, erroMensagem = null) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao alternar play/pause", e)
            CrashLogWriter.log(e, "AudioPlayerService.alternarPlayPause")
            return
        }
        PlayerStateHolder.update(_state.value)
        playerNotificationManager.atualizarNotificacao()
    }

    fun seek(posicao: Int, isArrastando: Boolean) {
        arrastandoSeek = isArrastando
        if (!isArrastando) exoPlayer.seekTo(posicao.toLong())
        _state.update { it.copy(currentPosition = posicao) }
    }

    fun seekRelativo(ms: Int) {
        val nova = (exoPlayer.currentPosition + ms).coerceIn(0L, duracaoConhecida())
        exoPlayer.seekTo(nova)
        _state.update { it.copy(currentPosition = nova.toInt()) }
    }

    /** Vai pro capítulo anterior (ou reinicia o atual); sem marcas de capítulo, recua 15s (T4.4). */
    fun capituloAnterior() {
        val alvo = capituloAnteriorMs(capitulosAtual, exoPlayer.currentPosition)
        if (alvo == null) {
            seekRelativo(-PULO_SEM_CAPITULO_MS)
            return
        }
        val nova = alvo.coerceIn(0L, duracaoConhecida())
        exoPlayer.seekTo(nova)
        _state.update { it.copy(currentPosition = nova.toInt()) }
    }

    /** Vai pro próximo capítulo; sem marcas de capítulo (ou já no último), avança 15s (T4.4). */
    fun proximoCapitulo() {
        val alvo = proximoCapituloMs(capitulosAtual, exoPlayer.currentPosition)
        if (alvo == null) {
            seekRelativo(PULO_SEM_CAPITULO_MS)
            return
        }
        val nova = alvo.coerceIn(0L, duracaoConhecida())
        exoPlayer.seekTo(nova)
        _state.update { it.copy(currentPosition = nova.toInt()) }
    }

    /** Cicla pelos presets da soneca (5→10→20→30→60→90→120→desligado→5…), acionado pelo botão
     * da notificação — mesmo padrão do [GuidedReadingService]. */
    fun alternarSonecaPreset() {
        val proximo = proximoPresetSonecaMin(SleepTimerManager.armedMinutes.value)
        if (proximo <= 0) SleepTimerManager.cancel() else SleepTimerManager.start(proximo * MS_POR_MINUTO)
        playerNotificationManager.atualizarNotificacao()
    }

    fun aplicarVelocidade(velocidade: Float) {
        _state.update { it.copy(speed = velocidade) }
        try { exoPlayer.playbackParameters = PlaybackParameters(velocidade) } catch (_: Exception) {}
    }

    fun fecharPlayer() {
        pararInterno()
        _state.value = PlayerState()
        PlayerStateHolder.update(PlayerState())
        PlayerWidget.atualizarWidget(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private fun uriPara(caminho: String): Uri =
        Uri.parse(caminho).let { if (it.scheme == null) Uri.fromFile(File(caminho)) else it }

    private fun AudiobookItem.paraMediaItem(): MediaItem {
        val mediaId = caminho ?: uri.toString()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(nome).setIsBrowsable(false).setIsPlayable(true).build()
            )
            .build()
    }

    private fun duracaoConhecida(): Long = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE

    /** Reseta o estado pro item que está entrando — chamado tanto por [iniciar] quanto por uma
     *  seleção feita no Android Auto (via [onAddMediaItems], aplicada pelo framework no mesmo player). */
    private fun prepararNovoItem(caminho: String, nome: String) {
        tickJob?.cancel()
        fileObserver?.stopWatching()
        fileObserver = null
        concluido = false
        aguardandoReady = true
        caminhoPreparado = caminho
        // Sem resetar isso aqui, uma troca de audiobook sem fechar o player antes herdava
        // capítulos do livro ANTERIOR (skip de capítulo saltava pra marca errada) e continuava
        // somando minutos ouvidos do livro anterior nas estatísticas do novo.
        capitulosAtual = emptyList()
        msOuvidoAcumulado = 0L
        ultimoSalvarProgressoMs = 0L
        _state.value = PlayerState(isVisible = true, trackName = nome, caminho = caminho, speed = _state.value.speed)
    }

    /** Bookkeeping que precisa da duração real (só confiável em STATE_READY, diferente do
     *  `MediaPlayer.prepare()` síncrono de antes): chapter marks, retomada de progresso, notificação. */
    private fun aoFicarPronto() {
        val caminho = _state.value.caminho
        capitulosAtual = ChapterMarksStore.carregar(applicationContext, caminho)
        val savedProgress = PlaybackProgressStore.load(applicationContext, caminho)
        val duracaoMs = duracaoConhecida()
        val duracaoInt = if (duracaoMs > Int.MAX_VALUE) Int.MAX_VALUE else duracaoMs.toInt()
        var posInicial = 0
        savedProgress?.positionMs
            ?.takeIf { it in MARGEM_RETOMADA_MS until (duracaoInt - MARGEM_FIM_RETOMADA_MS) }
            ?.let { pos -> (pos - recuoAutoRewindPara(caminho)).coerceAtLeast(0) }
            ?.let { posAjustada -> exoPlayer.seekTo(posAjustada.toLong()); posInicial = posAjustada }

        _state.update {
            it.copy(
                duration = duracaoInt,
                currentPosition = posInicial,
                isPlaying = exoPlayer.isPlaying,
                temCapitulos = capitulosAtual.isNotEmpty(),
                capitulos = capitulosAtual
            )
        }
        PlayerStateHolder.update(_state.value)
        monitorarArquivo(caminho)
        playerNotificationManager.iniciarForeground()
        iniciarTick()
    }

    private fun aoTerminar() {
        val caminho = _state.value.caminho
        val duracaoInt = _state.value.duration
        // Marca como concluído (100% na biblioteca) e reinicia do começo na próxima vez.
        PlaybackProgressStore.save(applicationContext, caminho, duracaoInt, duracaoInt)
        concluido = true
        StatsStore.registrarLivroConcluido(applicationContext, caminho)
        _state.update { it.copy(isPlaying = false, currentPosition = 0) }
        PlayerStateHolder.update(_state.value)
        PlayerWidget.atualizarWidget(applicationContext)
        playerNotificationManager.atualizarNotificacao()
        tickJob?.cancel()
        audioFocusController.abandonar()
        // Playback terminou de verdade: sai do estado foreground (perde a isenção de
        // prioridade/bateria) mas mantém a notificação visível e dispensável, igual ao
        // GuidedReadingService faz ao detectar fim de sessão. Sem isso o serviço ficava
        // preso em foreground indefinidamente até o usuário fechar manualmente o player.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    /** Salva a posição atual para retomada futura (ignora se a faixa já foi concluída). */
    private fun salvarProgresso() {
        if (concluido) return
        val dur = if (exoPlayer.duration > 0) exoPlayer.duration.toInt() else _state.value.duration
        val pos = try { exoPlayer.currentPosition.toInt() } catch (_: Exception) { _state.value.currentPosition }
        PlaybackProgressStore.save(applicationContext, _state.value.caminho, pos, dur)
    }

    /** Registra o instante da pausa (base para o cálculo de auto-rewind no próximo resume). */
    private fun marcarPausa() {
        if (concluido) return
        PlaybackProgressStore.markPaused(applicationContext, _state.value.caminho)
    }

    /** Recua a posição atual conforme o tempo pausado, se o auto-rewind estiver ativo. */
    private fun aplicarAutoRewindNoResume() {
        val recuo = recuoAutoRewindPara(_state.value.caminho)
        if (recuo <= 0) return
        val nova = (exoPlayer.currentPosition - recuo).coerceAtLeast(0)
        exoPlayer.seekTo(nova)
        _state.update { it.copy(currentPosition = nova.toInt()) }
    }

    /** Recuo (ms) do auto-rewind para [caminho], com base no tempo desde a última pausa registrada. */
    private fun recuoAutoRewindPara(caminho: String): Int {
        val pausadoEm = PlaybackProgressStore.pausedAtMs(applicationContext, caminho)
        if (pausadoEm <= 0L) return 0
        return calcularRecuoAutoRewind(appPrefs.autoRewindMode, System.currentTimeMillis() - pausadoEm)
    }

    private fun pararInterno() {
        salvarProgresso()
        if (exoPlayer.isPlaying) marcarPausa()
        tickJob?.cancel()
        fileObserver?.stopWatching()
        fileObserver = null
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        try { audioFocusController.abandonar() } catch (_: Exception) {}
    }

    private fun iniciarTick() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                if (_state.value.caminho.isBlank()) break
                if (!exoPlayer.isPlaying && _state.value.isPlaying) {
                    Log.w(TAG, "ExoPlayer parou inesperadamente. Limpando estado...")
                    _state.update { it.copy(isPlaying = false) }
                    PlayerStateHolder.update(_state.value)
                    playerNotificationManager.atualizarNotificacao()
                    tickJob?.cancel()
                    audioFocusController.abandonar()
                    break
                }
                if (!arrastandoSeek) {
                    val updated = _state.value.copy(currentPosition = exoPlayer.currentPosition.toInt())
                    _state.value = updated
                    PlayerStateHolder.update(updated)
                    MainActivity.widgetIsPlaying = updated.isPlaying
                    MainActivity.widgetTrackName = updated.trackName
                    PlayerWidget.atualizarWidget(applicationContext)
                    val agora = System.currentTimeMillis()
                    if (agora - ultimoSalvarProgressoMs >= INTERVALO_SALVAR_PROGRESSO_MS) {
                        salvarProgresso()
                        ultimoSalvarProgressoMs = agora
                    }
                    if (exoPlayer.isPlaying) registrarTickDeEscuta()
                }
            }
        }
    }

    /** Acumula tempo realmente tocando (tick/interação) e credita minuto inteiro nas estatísticas. */
    private fun registrarTickDeEscuta() {
        msOuvidoAcumulado += TICK_INTERVAL_MS
        if (msOuvidoAcumulado >= MS_POR_MINUTO) {
            StatsStore.registrarMinutosOuvidos(applicationContext, (msOuvidoAcumulado / MS_POR_MINUTO).toInt())
            msOuvidoAcumulado %= MS_POR_MINUTO
        }
    }

    private fun monitorarArquivo(caminho: String) {
        if (caminho.startsWith("content://")) return
        val arquivo = File(caminho)
        val dir     = arquivo.parentFile?.absolutePath ?: return
        fileObserver = object : FileObserver(dir, DELETE or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == arquivo.name) {
                    serviceScope.launch { fecharPlayer() }
                }
            }
        }
        fileObserver?.startWatching()
    }

}
