package com.jonjonesbr.audiobookgen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jonjonesbr.audiobookgen.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantém o processo vivo (e mostra o andamento) enquanto o capítulo é preparado em segundo plano.
 * O trabalho em si roda no [GuidedPlayerManager]; aqui só há a notificação e o botão de cancelar.
 */
class PreRenderService : Service() {

    companion object {
        private const val CHANNEL_ID = "lylyreader_prerender"
        private const val NOTIF_ID = 1003
        private const val ACTION_CANCEL = "com.jonjonesbr.audiobookgen.PRERENDER_CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, PreRenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            PreRenderBridge.cancelar?.invoke()
            return START_NOT_STICKY
        }
        criarCanal()
        val inicial = PreRenderBridge.state.value
        iniciarEmPrimeiroPlano(montar(inicial))
        observeJob?.cancel()
        observeJob = scope.launch {
            PreRenderBridge.state.collect { estado ->
                if (estado == null || estado.terminou) {
                    if (estado != null) notificarFim(estado)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, montar(estado))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun iniciarEmPrimeiroPlano(notificacao: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notificacao)
        }
    }

    private fun montar(estado: PreRenderState?): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, com.jonjonesbr.audiobookgen.ui.MeusLivrosActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelar = PendingIntent.getService(
            this, 1, Intent(this, PreRenderService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val texto = when {
            estado == null -> getString(R.string.prerender_notif_starting)
            estado.esperandoLeitura -> getString(R.string.prerender_notif_waiting, estado.feitos, estado.total)
            else -> getString(R.string.prerender_notif_progress, estado.feitos, estado.total)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.prerender_notif_title, estado?.titulo.orEmpty()))
            .setContentText(texto)
            .setProgress(100, ((estado?.fracao ?: 0f) * 100).toInt(), estado == null || estado.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(android.R.string.cancel), cancelar)
            .build()
    }

    private fun notificarFim(estado: PreRenderState) {
        val texto = when {
            estado.concluido -> getString(R.string.prerender_notif_done)
            estado.cancelado -> return
            else -> getString(R.string.prerender_notif_failed, estado.erro.orEmpty())
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.prerender_notif_title, estado.titulo))
            .setContentText(texto)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.prerender_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }
}
