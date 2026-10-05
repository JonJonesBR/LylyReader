package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * Constrói a notificação de mídia (ícones/labels condicionais a capítulo, contagem do sleep
 * timer) e mantém sua exibição. A partir da migração pra Android Auto (Fase 5, item 3), a
 * [MediaSession] (media3) já vem pronta de fora — construída e possuída por [AudioPlayerService]
 * — em vez desta classe criar a sua própria (era `MediaSessionCompat` antes). Comandos de
 * transporte (play/pause/seek/skip) não passam mais por callbacks aqui: a sessão media3
 * encaminha automaticamente pro [Player][androidx.media3.common.Player] vinculado.
 */
@OptIn(UnstableApi::class)
class PlayerNotificationManager(
    private val context: Context,
    private val obterEstado: () -> PlayerState,
    private val mediaSession: MediaSession,
    private val onStartForeground: (Notification) -> Unit,
) {
    companion object {
        const val CHANNEL_PLAYER_ID = "lylyreader_player"
        const val NOTIF_PLAYER_ID   = 1001
        const val ACTION_PLAY_PAUSE = "com.jonjonesbr.audiobookgen.PLAY_PAUSE"
        const val ACTION_RECUAR     = "com.jonjonesbr.audiobookgen.RECUAR"
        const val ACTION_AVANCAR    = "com.jonjonesbr.audiobookgen.AVANCAR"
        const val ACTION_FECHAR     = "com.jonjonesbr.audiobookgen.FECHAR"
        const val ACTION_SONECA     = "com.jonjonesbr.audiobookgen.PLAYER_SONECA"
        private const val REQ_RECUAR     = 1
        private const val REQ_PLAY_PAUSE = 2
        private const val REQ_AVANCAR    = 3
        private const val REQ_FECHAR     = 4
        private const val REQ_SONECA     = 5
    }

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        criarCanal()
    }

    fun iniciarForeground() {
        onStartForeground(construirNotificacao())
    }

    fun atualizarNotificacao() {
        try {
            nm.notify(NOTIF_PLAYER_ID, construirNotificacao())
        } catch (e: Exception) {
            // Antes engolia em silêncio — bug real relatado pelo usuário (player some da barra
            // de notificações, aleatório) não tinha NENHUM diagnóstico possível. Loga a exceção
            // real (ex.: SecurityException de permissão de notificação negada) pro próximo log
            // de erro apontar a causa em vez de só constatar o sintoma.
            CrashLogWriter.log(e, "notificacao.atualizar", "Falha ao atualizar notificação do player")
        }
    }

    /** Usado pelo [MediaNotification.Provider][androidx.media3.session.MediaNotification.Provider] do serviço,
     *  pra qualquer refresh que o media3 dispare por conta própria (ex.: ao conectar um controller). */
    fun construirNotificacaoAtual(): Notification = construirNotificacao()

    // ── Privados ──────────────────────────────────────────────────────────

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PLAYER_ID,
                "Player de Áudio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description  = "Controles de reprodução"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /** Texto da notificação: mensagem de erro de playback (P1) quando houver, senão o estado
     *  normal ("Reproduzindo"/"Pausado") com o sufixo do sleep timer quando armado. */
    private fun conteudoDaNotificacao(st: PlayerState): String {
        val erro = st.erroMensagem
        if (erro != null) return erro
        val baseText = if (st.isPlaying) "Reproduzindo" else "Pausado"
        val sonecaMs = SleepTimerManager.remainingMs.value
        return if (sonecaMs != null)
            "$baseText · 😴 ${SleepTimerManager.formatRemaining(sonecaMs)}" else baseText
    }

    private fun construirNotificacao(): Notification {
        val st = obterEstado()
        fun pending(action: String, req: Int) = PendingIntent.getService(
            context, req,
            Intent(context, AudioPlayerService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.jonjonesbr.audiobookgen.ui.LibraryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = conteudoDaNotificacao(st)
        return NotificationCompat.Builder(context, CHANNEL_PLAYER_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(st.trackName)
            .setContentText(contentText)
            .setContentIntent(openApp)
            .addAction(
                if (st.temCapitulos) android.R.drawable.ic_media_previous else android.R.drawable.ic_media_rew,
                if (st.temCapitulos) "Capítulo anterior" else "Recuar",
                pending(ACTION_RECUAR, REQ_RECUAR)
            )
            .addAction(
                if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.isPlaying) "Pausar" else "Reproduzir",
                pending(ACTION_PLAY_PAUSE, REQ_PLAY_PAUSE)
            )
            .addAction(
                if (st.temCapitulos) android.R.drawable.ic_media_next else android.R.drawable.ic_media_ff,
                if (st.temCapitulos) "Próximo capítulo" else "Avançar",
                pending(ACTION_AVANCAR, REQ_AVANCAR)
            )
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                SleepTimerManager.armedMinutes.value?.let { "Soneca ${it}min" } ?: "Soneca",
                pending(ACTION_SONECA, REQ_SONECA)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar", pending(ACTION_FECHAR, REQ_FECHAR))
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(st.isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
