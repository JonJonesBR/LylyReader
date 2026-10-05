package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.proximoPresetSonecaMin
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        fun start(context: Context) {
            val intent = Intent(context, GuidedReadingService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        // Sleep timer: atualiza a contagem na notificação e pausa a leitura guiada ao terminar.
        scope.launch {
            SleepTimerManager.remainingMs.collect { refreshNotificacao() }
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
                    if (m.state.value.isPlaying) m.pause()
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
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                observarEstado()
            }
            ACTION_PLAY_PAUSE -> {
                val m = GuidedPlaybackBridge.manager
                if (m?.state?.value?.isPlaying == true) m.pause() else m?.resume()
            }
            ACTION_PREV -> GuidedPlaybackBridge.manager?.previous()
            ACTION_NEXT -> GuidedPlaybackBridge.manager?.next()
            ACTION_SLEEP -> {
                // Cicla pelos presets (5→10→20→30→60→90→120→desligado→5…), travada no token
                // da sessão vigente (ver SleepTimerManager.armedToken).
                val proximo = proximoPresetSonecaMin(SleepTimerManager.armedMinutes.value)
                if (proximo <= 0) {
                    SleepTimerManager.cancel()
                } else {
                    SleepTimerManager.start(proximo * MS_POR_MINUTO, GuidedPlaybackBridge.currentToken)
                }
                refreshNotificacao()
            }
            ACTION_STOP -> {
                GuidedPlaybackBridge.manager?.stop()
                pararServico()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ativo = false
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
    }

    private fun observarEstado() {
        observeJob?.cancel()
        val manager = GuidedPlaybackBridge.manager ?: return
        val token = manager.sessionToken
        observeJob = scope.launch {
            manager.state.collect { st ->
                if (st.index == -1) {
                    // Só encerra o serviço se este evento ainda vier da sessão vigente. Uma
                    // sessão antiga (já substituída por um novo GuidedPlayerManager, ex.:
                    // troca de livro) que emita index=-1 atrasada não deve derrubar o serviço
                    // que já está a serviço da sessão nova.
                    if (GuidedPlaybackBridge.currentToken == token) {
                        pararServico()
                    }
                } else {
                    atualizarEstadoMediaSession(st.isPlaying)
                    notificar(construirNotificacao(st.index, st.isPlaying))
                }
            }
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Leitura Guiada",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles da leitura guiada"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun atualizarEstadoMediaSession(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
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
        val contentText = if (sonecaMs != null)
            "Parágrafo ${index + 1} · 😴 ${SleepTimerManager.formatRemaining(sonecaMs)}"
        else "Parágrafo ${index + 1}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(GuidedPlaybackBridge.trackName)
            .setContentText(contentText)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", pending(ACTION_PREV, 1))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausar" else "Reproduzir",
                pending(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Próximo", pending(ACTION_NEXT, PENDING_REQ_NEXT))
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                SleepTimerManager.armedMinutes.value?.let { "Soneca ${it}min" } ?: "Soneca",
                pending(ACTION_SLEEP, PENDING_REQ_SLEEP)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar", pending(ACTION_STOP, PENDING_REQ_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
