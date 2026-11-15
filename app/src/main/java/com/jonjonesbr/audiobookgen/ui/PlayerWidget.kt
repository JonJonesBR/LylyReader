package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.PlayerNotificationManager
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder

class PlayerWidget : AppWidgetProvider() {

    companion object {
        // Códigos de PendingIntent distintos dos REQ_* da notificação (PlayerNotificationManager,
        // 1-5) — mesmo target (AudioPlayerService) e mesma action, mas melhor não compartilhar o
        // código de requisição entre os dois (cancelar um não deve afetar o outro).
        private const val REQ_WIDGET_PLAY_PAUSE = 12
        private const val REQ_WIDGET_RECUAR = 10
        private const val REQ_WIDGET_AVANCAR = 11

        fun atualizarWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PlayerWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, PlayerWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, construirViews(context))
        }
    }

    internal fun construirViews(
        context: Context,
        criarPendingServico: (Context, Int, Intent, Int) -> PendingIntent = PendingIntent::getForegroundService,
        criarPendingAbrir: (Context, Int, Intent, Int) -> PendingIntent = PendingIntent::getActivity
    ): RemoteViews {
        val views   = RemoteViews(context.packageName, R.layout.widget_player)
        val estadoPlayer = PlayerStateHolder.state.value
        val nome    = estadoPlayer.trackName
        val tocando = estadoPlayer.isPlaying

        // Track name and state label
        views.setTextViewText(R.id.widgetTvNome, nome.ifBlank { context.getString(R.string.app_name) })
        views.setTextViewText(
            R.id.widgetTvEstado,
            when {
                nome.isBlank() -> context.getString(R.string.widget_toque_para_abrir)
                tocando        -> context.getString(R.string.widget_reproduzindo)
                else           -> context.getString(R.string.widget_pausado)
            }
        )

        // Play/Pause icon + white tint
        views.setImageViewResource(
            R.id.widgetBtnPlayPause,
            if (tocando) android.R.drawable.ic_media_pause
            else         android.R.drawable.ic_media_play
        )
        views.setInt(R.id.widgetBtnPlayPause, "setColorFilter", Color.WHITE)

        // Clique no título/estado → abre o app (também é o fallback do Play quando não há livro carregado).
        val openIntent = Intent(context, MeusLivrosActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = criarPendingAbrir(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetTvNome,   openPi)
        views.setOnClickPendingIntent(R.id.widgetTvEstado, openPi)

        // Navegação por capítulo e Play/Pause: usam PendingIntent.getForegroundService (API 26+
        // recusa getService em segundo plano ou com o processo morto) e levam caminho/nome para
        // retomar o último livro se o processo tiver morrido. Sem faixa carregada, o Play abre o app.
        val temFaixaCarregada = nome.isNotBlank()
        val visibilidadeNav = if (temFaixaCarregada) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widgetBtnCapituloAnterior, visibilidadeNav)
        views.setViewVisibility(R.id.widgetBtnProximoCapitulo, visibilidadeNav)

        fun pendingServico(action: String, req: Int): PendingIntent {
            val svcIntent = Intent(context, AudioPlayerService::class.java).apply {
                this.action = action
                if (estadoPlayer.caminho.isNotBlank()) {
                    putExtra(AudioPlayerService.EXTRA_CAMINHO, estadoPlayer.caminho)
                    putExtra(AudioPlayerService.EXTRA_NOME, nome)
                }
            }
            return criarPendingServico(
                context, req, svcIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val togglePi = if (temFaixaCarregada) {
            pendingServico(PlayerNotificationManager.ACTION_PLAY_PAUSE, REQ_WIDGET_PLAY_PAUSE)
        } else {
            openPi
        }
        views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, togglePi)

        if (temFaixaCarregada) {
            views.setOnClickPendingIntent(
                R.id.widgetBtnCapituloAnterior,
                pendingServico(PlayerNotificationManager.ACTION_RECUAR, REQ_WIDGET_RECUAR)
            )
            views.setOnClickPendingIntent(
                R.id.widgetBtnProximoCapitulo,
                pendingServico(PlayerNotificationManager.ACTION_AVANCAR, REQ_WIDGET_AVANCAR)
            )
        }

        return views
    }
}
