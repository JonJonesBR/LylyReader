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
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.domain.fracaoAgregada
import com.jonjonesbr.audiobookgen.ui.DownloadCentralActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantém o processo vivo e mostra UMA notificação agregada enquanto a [DownloadCentral] baixa pacotes.
 * O trabalho roda na central; aqui há só a notificação, o "Pausar tudo" e o aviso de fim.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "lylyreader_downloads"
        private const val NOTIF_ID = 1004
        private const val ACTION_PAUSAR_TUDO = "com.jonjonesbr.audiobookgen.DOWNLOADS_PAUSAR_TUDO"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            // Pode ser recusado se o app estiver em 2º plano sem permissão para iniciar serviço.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private val vistosBaixando = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSAR_TUDO) {
            DownloadCentral.pausarTodos()
            return START_NOT_STICKY
        }
        criarCanal()
        iniciarEmPrimeiroPlano(montar(emptyMap()))
        observeJob?.cancel()
        observeJob = scope.launch {
            DownloadCentral.andamento.collect { mapa ->
                vistosBaixando += mapa.filterValues { it.estado == EstadoDownload.BAIXANDO }.keys
                if (!DownloadCentral.temAtividade()) {
                    notificarFim(mapa)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, montar(mapa))
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

    private fun montar(mapa: Map<String, AndamentoDownload>): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadCentralActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pausar = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSAR_TUDO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val baixando = mapa.values.filter { it.estado == EstadoDownload.BAIXANDO }
        val naFila = mapa.values.count { it.estado == EstadoDownload.NA_FILA }
        val fracao = fracaoAgregada(baixando.map { it.bytes to it.total })
        val texto = if (fracao == null) {
            getString(R.string.dl_notif_iniciando)
        } else {
            getString(R.string.dl_notif_texto, (fracao * 100).toInt(), baixando.size, naFila)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.dl_notif_titulo))
            .setContentText(texto)
            .setProgress(100, ((fracao ?: 0f) * 100).toInt(), fracao == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.dl_notif_pausar_tudo), pausar)
            .build()
    }

    private fun notificarFim(mapa: Map<String, AndamentoDownload>) {
        val terminados = vistosBaixando.mapNotNull { mapa[it]?.estado }
        vistosBaixando.clear()
        val texto = when {
            terminados.any { it == EstadoDownload.ERRO } -> getString(R.string.dl_notif_falha)
            terminados.any { it == EstadoDownload.PRONTO } -> getString(R.string.dl_notif_fim)
            else -> return // só pausou/cancelou: sem aviso
        }
        val abrir = PendingIntent.getActivity(
            this, 2, Intent(this, DownloadCentralActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.dl_notif_titulo))
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.dl_notif_canal), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }
}
