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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.PlayerNotificationManager

class PlayerWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.jonjonesbr.audiobookgen.WIDGET_TOGGLE"
        // Códigos de PendingIntent distintos dos REQ_* da notificação (PlayerNotificationManager,
        // 1-5) — mesmo target (AudioPlayerService) e mesma action, mas melhor não compartilhar o
        // código de requisição entre os dois (cancelar um não deve afetar o outro).
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            // Forward to MainActivity via LocalBroadcastManager (works if process is alive)
            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent(ACTION_WIDGET_TOGGLE))
        }
    }

    private fun construirViews(context: Context): RemoteViews {
        val views   = RemoteViews(context.packageName, R.layout.widget_player)
        val nome    = MainActivity.widgetTrackName
        val tocando = MainActivity.widgetIsPlaying

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

        // Button click → toggle playback
        val toggleIntent = Intent(context, PlayerWidget::class.java).apply {
            action = ACTION_WIDGET_TOGGLE
        }
        val togglePi = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetBtnPlayPause, togglePi)

        // Navegação por capítulo: mesma ação (RECUAR/AVANCAR) que os botões da notificação já
        // usam, direto no AudioPlayerService (PendingIntent.getService — igual
        // PlayerNotificationManager.construirNotificacao) em vez do relay por
        // LocalBroadcastManager do play/pause acima, que só funciona com o processo vivo. O
        // service já cai pra "avançar/recuar 15s" quando o livro não tem capítulos (mesmo
        // fallback da notificação) — nunca precisa ficar desabilitado por causa disso, só
        // quando não há NADA carregado (nome em branco, mesmo estado vazio do texto acima).
        val temFaixaCarregada = nome.isNotBlank()
        val visibilidadeNav = if (temFaixaCarregada) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widgetBtnCapituloAnterior, visibilidadeNav)
        views.setViewVisibility(R.id.widgetBtnProximoCapitulo, visibilidadeNav)
        if (temFaixaCarregada) {
            fun pendingServico(action: String, req: Int) = PendingIntent.getService(
                context, req,
                Intent(context, AudioPlayerService::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                R.id.widgetBtnCapituloAnterior,
                pendingServico(PlayerNotificationManager.ACTION_RECUAR, REQ_WIDGET_RECUAR)
            )
            views.setOnClickPendingIntent(
                R.id.widgetBtnProximoCapitulo,
                pendingServico(PlayerNotificationManager.ACTION_AVANCAR, REQ_WIDGET_AVANCAR)
            )
        }

        // Name/state click → open app
        val openIntent = Intent(context, MeusLivrosActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetTvNome,   openPi)
        views.setOnClickPendingIntent(R.id.widgetTvEstado, openPi)

        return views
    }
}
