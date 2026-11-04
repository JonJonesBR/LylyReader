package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.proximoPresetSonecaMin
import com.jonjonesbr.audiobookgen.util.textosDaSoneca
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Comando que uma ação do Intent do [GuidedReadingService] pede. */
internal enum class ComandoLeituraGuiada { INICIAR, ALTERNAR_REPRODUCAO, ANTERIOR, PROXIMO, SONECA, PARAR }

/** Mapeia a ação do Intent ao comando; ação ausente ou desconhecida = null (nada a fazer). Função pura. */
internal fun acaoDoComando(action: String?): ComandoLeituraGuiada? = when (action) {
    GuidedReadingService.ACTION_START -> ComandoLeituraGuiada.INICIAR
    GuidedReadingService.ACTION_PLAY_PAUSE -> ComandoLeituraGuiada.ALTERNAR_REPRODUCAO
    GuidedReadingService.ACTION_PREV -> ComandoLeituraGuiada.ANTERIOR
    GuidedReadingService.ACTION_NEXT -> ComandoLeituraGuiada.PROXIMO
    GuidedReadingService.ACTION_SLEEP -> ComandoLeituraGuiada.SONECA
    GuidedReadingService.ACTION_STOP -> ComandoLeituraGuiada.PARAR
    else -> null
}

/**
 * Ações que a sessão de mídia da leitura guiada anuncia. Sem ACTION_SEEK_TO (decisão do rb-41): nos motores
 * locais o trecho é uma oração de 2 a 8 s, e arrastar a barra não serve para quase nada. Além disso há corrida
 * com o trecho que acaba (o seek cairia no player seguinte ou num já terminado), e no vão entre orações não há
 * o que buscar. Sem a ação, o sistema desenha a barra só de leitura, com o progresso do trecho.
 */
internal fun acoesDaSessaoGuiada(): Long =
    PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_STOP

/**
 * Estado da sessão. Com [trecho], leva a posição do trecho que está no player, o instante ([agoraMs],
 * elapsedRealtime) em que ela foi lida e a velocidade efetiva: o sistema anima a barra sozinho, sem tique.
 * Pausado, a velocidade é 0 e a barra fica parada. Sem trecho, a posição é desconhecida (início da sessão,
 * antes do 1º trecho). PLAYING com velocidade 0 é válido entre orações: o ícone segue "pausar" e a barra
 * fica parada no fim do trecho.
 */
internal fun estadoDaSessaoDaGuiada(
    tocando: Boolean,
    trecho: TrechoNaSessao? = null,
    agoraMs: Long = 0L
): PlaybackStateCompat {
    val estado = if (tocando) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
    val builder = PlaybackStateCompat.Builder().setActions(acoesDaSessaoGuiada())
    return if (trecho == null) {
        builder.setState(estado, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (tocando) 1f else 0f).build()
    } else {
        builder.setState(estado, trecho.posicaoMs, if (tocando) trecho.velocidade else 0f, agoraMs).build()
    }
}

/**
 * Metadados da sessão. A duração é a do trecho que está tocando (rb-41): a barra da notificação, no SystemUI do
 * fabricante, mostra esse progresso. Sem trecho, a duração é -1 (desconhecida), como no início da sessão.
 */
internal fun metadataDaSessaoGuiada(
    titulo: String,
    nomeApp: String,
    duracaoMs: Long = DURACAO_DESCONHECIDA
): MediaMetadataCompat =
    MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titulo)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nomeApp)
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duracaoMs)
        .build()

/** Duração informada à sessão quando o livro não tem tempo total (negativo = desconhecida). */
private const val DURACAO_DESCONHECIDA = -1L

/**
 * Foreground service da leitura guiada.
 *
 * Não reproduz áudio diretamente — o [GuidedPlayerManager] (na ReaderActivity) continua
 * dono do player. Este serviço apenas:
 *   1. Mantém o processo em prioridade foreground enquanto a leitura guiada está ativa,
 *      evitando que o Android encerre a reprodução quando o app vai para 2º plano (#4).
 *   2. Exibe a notificação de mídia com controles (anterior/play-pause/próximo/fechar).
 *
 * O estado é lido de [GuidedPlaybackBridge.manager] e os botões são roteados de volta
 * para o manager. O serviço se encerra sozinho quando o estado chega a index == -1.
 */
// Leitura guiada opera sobre um documento já aberto, não um catálogo navegável — não há o
// que "buscar por voz" aqui (isso é do MediaLibraryService do AudioPlayerService).
@SuppressLint("MissingOnPlayFromSearch")
class GuidedReadingService : Service() {

    companion object {
        const val CHANNEL_ID = "lylyreader_guided"
        const val NOTIF_ID   = 1002
        const val ACTION_START      = "com.jonjonesbr.audiobookgen.GUIDED_START"
        const val ACTION_PLAY_PAUSE = "com.jonjonesbr.audiobookgen.GUIDED_PLAY_PAUSE"
        const val ACTION_PREV       = "com.jonjonesbr.audiobookgen.GUIDED_PREV"
        const val ACTION_NEXT       = "com.jonjonesbr.audiobookgen.GUIDED_NEXT"
        const val ACTION_STOP       = "com.jonjonesbr.audiobookgen.GUIDED_STOP"
        const val ACTION_SLEEP      = "com.jonjonesbr.audiobookgen.GUIDED_SLEEP"

        private const val MS_POR_MINUTO = 60_000L
        // Request codes dos PendingIntent da notificação (1 e 2 não precisam de constante —
        // o detekt ignora valores <= 2 por padrão).
        private const val PENDING_REQ_NEXT  = 3
        private const val PENDING_REQ_STOP  = 4
        private const val PENDING_REQ_SLEEP = 5

        // Fonte da verdade de "o foreground service está rodando", independente de qual
        // GuidedPlayerManager está ativo — cada troca de livro cria uma instância nova do
        // manager, então um campo de instância lá resetaria implicitamente a cada troca.
        // Vive no companion (nível de classe) porque o processo só mantém um Service ativo
        // por vez; setado em onCreate()/onDestroy(), os únicos pontos que refletem o ciclo
        // de vida real do serviço.
        @Volatile
        var ativo: Boolean = false
            private set

        /** Token da sessão que a notificação acompanha agora (null: nenhuma). */
        @Volatile
        var tokenObservado: Long? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, GuidedReadingService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GuidedReadingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var mediaSession: MediaSessionCompat? = null
    // Título que a MediaSession já mostra: a fila troca de livro sem trocar de sessão, e o título precisa acompanhar.
    private var tituloNaSessao: String? = null
    // Duração do trecho que a MediaSession já mostra (rb-41): muda a cada trecho, então também precisa ser
    // acompanhada.
    private var duracaoNaSessao: Long? = null
    // Horário fixo da notificação: com o horário da atualização, ela trocava de lugar com a do preparo (rb-33).
    private val inicioDaNotificacao = System.currentTimeMillis()

    // Foco de áudio da narração. Sem ele, ligações, GPS e outros apps tocavam por cima da voz, em volume cheio.
    // Perda temporária (ligação) pausa e retoma sozinha ao voltar; perda definitiva (outro app de mídia) só pausa.
    // Avisos curtos e GPS: a partir do Android 8 o próprio sistema abaixa o volume, sem passar por aqui.
    private var temFocoDeAudio = false
    private var retomarAoGanharFoco = false
    private val focoDeAudio by lazy {
        AudioFocusController(
            context = applicationContext,
            onFocusLost = { perderFocoDeAudio(retomarDepois = false) },
            onFocusLostTransient = { perderFocoDeAudio(retomarDepois = true) },
            onFocusGained = {
                temFocoDeAudio = true
                if (retomarAoGanharFoco) {
                    retomarAoGanharFoco = false
                    GuidedPlaybackBridge.manager?.takeIf { it.estaPausado }?.resume()
                }
            },
            onDuck = {},
            tipoDeConteudo = AudioAttributes.CONTENT_TYPE_SPEECH,
        )
    }

    // Chamada tocando ou em andamento pelo modo de áudio do sistema (Android 12+). Cobre apps de chamada pela
    // internet, como o WhatsApp, que tocam sem pedir o foco de áudio: a narração seguia por cima do toque.
    // Pausa ao começar e retoma quando o modo volta ao normal, se foi esta pausa que a parou.
    private var pausadoPorChamada = false
    private val ouvinteModoDeAudio: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { modo -> aoMudarModoDeAudio(modo) }
        } else {
            null
        }

    // Fone desconectado (cabo ou Bluetooth): pausa, em vez de a voz passar a sair pelo alto-falante.
    private val receptorFoneDesconectado = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            retomarAoGanharFoco = false
            pausadoPorChamada = false
            val m = GuidedPlaybackBridge.manager ?: return
            if (m.state.value.index >= 0) m.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ativo = true
        criarCanal()
        mediaSession = MediaSessionCompat(this, "LylyReaderGuided").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val m = GuidedPlaybackBridge.manager
                    m?.resume()
                }

                override fun onPause() {
                    val m = GuidedPlaybackBridge.manager
                    m?.pause()
                }

                override fun onSkipToNext() {
                    GuidedPlaybackBridge.manager?.next()
                }

                override fun onSkipToPrevious() {
                    GuidedPlaybackBridge.manager?.previous()
                }

                override fun onStop() {
                    GuidedPlaybackBridge.manager?.stop()
                    pararServico()
                }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, receptorFoneDesconectado, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                getSystemService(AudioManager::class.java).addOnModeChangedListener(
                    mainExecutor, ouvinteModoDeAudio as AudioManager.OnModeChangedListener
                )
            }.onFailure { CrashLogWriter.log(it, "GuidedReadingService.modoDeAudio") }
        }
        // Sleep timer: atualiza a contagem na notificação e pausa a leitura guiada ao terminar.
        scope.launch {
            SleepTimerManager.remainingMs.collect { refreshNotificacao() }
        }
        // Soneca "fim do capítulo" armada ou disparada: o texto e o botão da notificação mudam sem contagem.
        scope.launch {
            SleepTimerManager.fimDoCapitulo.collect { refreshNotificacao() }
        }
        scope.launch {
            SleepTimerManager.finished.collect {
                // A soneca só pausa a sessão para a qual foi ligada (token salvo em
                // SleepTimerManager.armedToken no momento do start()). Se a leitura guiada já
                // trocou de sessão (novo livro) nesse meio-tempo, o token não bate mais e a
                // soneca não deve pausar a leitura errada.
                val tokenArmado = SleepTimerManager.armedToken
                val m = GuidedPlaybackBridge.manager
                if (m != null && tokenArmado != null && tokenArmado == m.sessionToken) {
                    // Pausa também no meio da síntese do próximo trecho (isPlaying ainda false): pause() segura o
                    // laço em aguardarRetomada() e o trecho não chega a tocar. Só não há o que pausar sem leitura (-1).
                    // A soneca também cancela a volta automática depois de uma ligação.
                    retomarAoGanharFoco = false
                    pausadoPorChamada = false
                    if (m.state.value.index >= 0) m.pause()
                } else if (tokenArmado != null) {
                    SleepTimerManager.cancel()
                }
            }
        }
    }

    /** Rebuilda a notificação a partir do estado atual (usado quando só o timer mudou). */
    private fun refreshNotificacao() {
        val st = GuidedPlaybackBridge.manager?.state?.value ?: return
        if (st.index == -1) return
        notificar(construirNotificacao(st.index, st.isPlaying))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (acaoDoComando(intent?.action)) {
            ComandoLeituraGuiada.INICIAR -> {
                startForegroundCompat()
                observarEstado()
            }
            ComandoLeituraGuiada.ALTERNAR_REPRODUCAO -> {
                val m = GuidedPlaybackBridge.manager
                if (m?.state?.value?.isPlaying == true) m.pause() else m?.resume()
            }
            ComandoLeituraGuiada.ANTERIOR -> GuidedPlaybackBridge.manager?.previous()
            ComandoLeituraGuiada.PROXIMO -> GuidedPlaybackBridge.manager?.next()
            ComandoLeituraGuiada.SONECA -> {
                // Cicla pelos presets (5→10→20→30→60→90→120→desligado→5…), travada no token
                // da sessão vigente (ver SleepTimerManager.armedToken). Do modo "fim do capítulo" (sem
                // minutos), armedMinutes é null: o próximo preset é 5 min, e start() desliga o modo capítulo.
                val proximo = proximoPresetSonecaMin(SleepTimerManager.armedMinutes.value)
                if (proximo <= 0) {
                    SleepTimerManager.cancel()
                } else {
                    SleepTimerManager.start(proximo * MS_POR_MINUTO, GuidedPlaybackBridge.currentToken)
                }
                refreshNotificacao()
            }
            ComandoLeituraGuiada.PARAR -> {
                GuidedPlaybackBridge.manager?.stop()
                pararServico()
            }
            null -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ativo = false
        tokenObservado = null
        scope.cancel()
        runCatching { unregisterReceiver(receptorFoneDesconectado) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                getSystemService(AudioManager::class.java)
                    .removeOnModeChangedListener(ouvinteModoDeAudio as AudioManager.OnModeChangedListener)
            }
        }
        abandonarFocoDeAudio()
        mediaSession?.release()
        mediaSession = null
    }

    /** Pede o foco ao começar a tocar. Negado (ex.: durante uma ligação) pausa; adiado retoma quando vier. */
    private fun garantirFocoDeAudio(manager: GuidedPlayerManager) {
        if (temFocoDeAudio) return
        when (focoDeAudio.solicitar()) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                temFocoDeAudio = true
                retomarAoGanharFoco = false
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                manager.pause()
                retomarAoGanharFoco = true
            }
            else -> manager.pause()
        }
    }

    private fun perderFocoDeAudio(retomarDepois: Boolean) {
        temFocoDeAudio = false
        val m = GuidedPlaybackBridge.manager ?: return
        val st = m.state.value
        // Tocando ou preparando o próximo trecho, sem pausa do usuário: só então pausa e (se temporária) retoma depois.
        val tocando = st.index >= 0 && !m.estaPausado && (st.isPlaying || st.isLoading)
        if (tocando) m.pause()
        retomarAoGanharFoco = retomarDepois && tocando
    }

    private fun aoMudarModoDeAudio(modo: Int) {
        val m = GuidedPlaybackBridge.manager ?: return
        if (modo == AudioManager.MODE_NORMAL) {
            if (pausadoPorChamada) {
                pausadoPorChamada = false
                if (m.estaPausado && m.state.value.index >= 0) m.resume()
            }
            return
        }
        // Qualquer outro modo (toque, ligação, chamada pela internet, triagem): há uma chamada no aparelho.
        val st = m.state.value
        val tocando = st.index >= 0 && !m.estaPausado && (st.isPlaying || st.isLoading)
        if (tocando) {
            m.pause()
            pausadoPorChamada = true
        }
    }

    private fun abandonarFocoDeAudio() {
        if (temFocoDeAudio || retomarAoGanharFoco) focoDeAudio.abandonar()
        temFocoDeAudio = false
        retomarAoGanharFoco = false
        pausadoPorChamada = false
    }

    private fun observarEstado() {
        observeJob?.cancel()
        val manager = GuidedPlaybackBridge.manager ?: return
        val token = manager.sessionToken
        tokenObservado = token
        // Sessão nova (outro manager): zera o que a MediaSession mostrava, para a 1ª publicação mandar tudo
        // de novo.
        tituloNaSessao = null
        duracaoNaSessao = null
        atualizarMetadados(manager.trackName, manager.trechoNaSessao.value?.duracaoMs ?: DURACAO_DESCONHECIDA)
        observeJob = scope.launch {
            // Trecho que começa, para, retoma ou troca de velocidade: a MediaSession é publicada só nesses eventos,
            // nunca no tique. Filho deste job: morre com ele no pararServico() e numa nova observação.
            launch { manager.trechoNaSessao.collect { publicarSessao(manager) } }
            // O estado é publicado a cada ~50 ms (progresso do parágrafo); a notificação e a MediaSession só
            // mostram parágrafo e play/pausa, então só se refazem quando um desses muda (a soneca tem coletor
            // próprio). Antes eram ~20 notify() por segundo: bateria, main thread e notificações descartadas.
            manager.state.map { it.index to it.isPlaying }.distinctUntilChanged().collect { (index, isPlaying) ->
                if (index == -1) {
                    // Só encerra o serviço se este evento ainda vier da sessão vigente. Uma
                    // sessão antiga (já substituída por um novo GuidedPlayerManager, ex.:
                    // troca de livro) que emita index=-1 atrasada não deve derrubar o serviço
                    // que já está a serviço da sessão nova.
                    // stop() desliga o bridge ANTES de publicar index=-1, então aqui o token vigente já é
                    // null: antes o serviço não parava e ficava preso à sessão encerrada (notificação
                    // congelada e play/pausa sem efeito na sessão seguinte).
                    val vigente = GuidedPlaybackBridge.currentToken
                    if (vigente == null || vigente == token) {
                        abandonarFocoDeAudio()
                        pararServico()
                    }
                } else {
                    if (isPlaying) garantirFocoDeAudio(manager)
                    // Depois do foco: se ele negou e pausou, a sessão já sai como PAUSED.
                    publicarSessao(manager)
                    notificar(construirNotificacao(index, isPlaying))
                }
            }
        }
    }

    /**
     * Publica na MediaSession o título, a duração do trecho e o estado (posição e velocidade). Chamada só nos
     * eventos do manager. Os metadados vêm antes do estado: a barra nova não pode aparecer com a duração velha.
     */
    private fun publicarSessao(manager: GuidedPlayerManager) {
        val st = manager.state.value
        if (st.index < 0) return
        val trecho = manager.trechoNaSessao.value
        // A fila "Ler tudo" trocou de livro na mesma sessão: a tela de bloqueio e o Bluetooth seguem o título novo.
        val titulo = GuidedPlaybackBridge.trackName(manager.trackName)
        val duracao = trecho?.duracaoMs ?: DURACAO_DESCONHECIDA
        // Título e duração só são reenviados quando mudam (a duração muda a cada trecho).
        if (titulo != tituloNaSessao || duracao != duracaoNaSessao) atualizarMetadados(titulo, duracao)
        // O instante é o da leitura da posição (no manager), não o desta chamada: o coletor pode chegar depois.
        mediaSession?.setPlaybackState(estadoDaSessaoDaGuiada(st.isPlaying, trecho, trecho?.instanteMs ?: 0L))
    }

    /** Título do livro na tela de bloqueio, no relógio e no carro (Bluetooth); antes ficava sem nome. */
    private fun atualizarMetadados(titulo: String, duracaoMs: Long) {
        tituloNaSessao = titulo
        duracaoNaSessao = duracaoMs
        mediaSession?.setMetadata(metadataDaSessaoGuiada(titulo, getString(R.string.app_name), duracaoMs))
    }

    private fun startForegroundCompat() {
        val st = GuidedPlaybackBridge.manager?.state?.value
        val notif = construirNotificacao(st?.index ?: 0, st?.isPlaying ?: true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun pararServico() {
        observeJob?.cancel()
        observeJob = null
        tokenObservado = null
        tituloNaSessao = null
        duracaoNaSessao = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun criarCanal() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.action_guided_reading),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_guiada_canal_descricao)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificar(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            // Antes engolia em silêncio — bug real relatado pelo usuário (player some da barra
            // de notificações, aleatório) não tinha NENHUM diagnóstico possível. Loga a exceção
            // real (ex.: SecurityException de permissão de notificação negada) pro próximo log
            // de erro apontar a causa em vez de só constatar o sintoma.
            CrashLogWriter.log(e, "notificacao.leituraGuiada", "Falha ao atualizar notificação da leitura guiada")
        }
    }

    private fun construirNotificacao(index: Int, isPlaying: Boolean): Notification {
        fun pending(action: String, req: Int) = PendingIntent.getService(
            this, req,
            Intent(this, GuidedReadingService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Tocar a notificação reabre a ReaderActivity no documento em leitura (reconecta na
        // posição). Mantém MainActivity como pai para o Back/Up funcionarem.
        val openApp = if (GuidedPlaybackBridge.caminhoArquivo.isNotEmpty()) {
            androidx.core.app.TaskStackBuilder.create(this)
                .addNextIntent(Intent(this, com.jonjonesbr.audiobookgen.ui.MeusLivrosActivity::class.java))
                .addNextIntent(
                    Intent(this, com.jonjonesbr.audiobookgen.ui.ReaderActivity::class.java)
                        .putExtra(
                            com.jonjonesbr.audiobookgen.ui.ReaderActivity.EXTRA_CAMINHO,
                            GuidedPlaybackBridge.caminhoArquivo
                        )
                )
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, com.jonjonesbr.audiobookgen.ui.MeusLivrosActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val sonecaMs = SleepTimerManager.remainingMs.value
        val sonecaCapitulo = SleepTimerManager.fimDoCapitulo.value
        val contentText = if (SleepTimerManager.isActive)
            "${getString(R.string.notif_paragrafo, index + 1)} · ${textosDaSoneca(sonecaMs, sonecaCapitulo).rotulo}"
        else getString(R.string.notif_paragrafo, index + 1)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(GuidedPlaybackBridge.trackName(getString(R.string.btn_leitura_guiada)))
            .setContentText(contentText)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.notif_anterior), pending(ACTION_PREV, 1))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) getString(R.string.conv_pausar) else getString(R.string.notif_reproduzir),
                pending(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, getString(R.string.onboarding_btn_proximo), pending(ACTION_NEXT, PENDING_REQ_NEXT))
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                when {
                    sonecaCapitulo -> getString(R.string.notif_soneca_fim_capitulo)
                    else -> SleepTimerManager.armedMinutes.value?.let { getString(R.string.notif_soneca_minutos, it) }
                        ?: getString(R.string.cd_sleep_timer)
                },
                pending(ACTION_SLEEP, PENDING_REQ_SLEEP)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.conv_fechar), pending(ACTION_STOP, PENDING_REQ_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(inicioDaNotificacao)
            .setShowWhen(false)
            .build()
    }
}
