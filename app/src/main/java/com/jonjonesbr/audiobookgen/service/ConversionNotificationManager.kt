package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.MainActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ConversionNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID  = "lylyreader_conversion"
        const val NOTIF_ID    = 1002

        /** Percentual máximo da barra de progresso da notificação. */
        private const val PROGRESSO_MAX = 100
    }

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { criarCanal() }

    fun mostrar(progresso: Int, mensagem: String) {
        val intent = Intent(context, com.jonjonesbr.audiobookgen.ui.ConversoesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_conversion_progress))
            .setContentText(mensagem)
            .setProgress(PROGRESSO_MAX, progresso, progresso == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    fun cancelar() = nm.cancel(NOTIF_ID)

    /** [caminho]/[nome] do audiobook recém-gerado — tocar na notificação abre a decisão de
     * player (app ou "Abrir com...", ver [MainActivity.EXTRA_ABRIR_AUDIOBOOK_CAMINHO]). */
    fun mostrarConcluida(caminho: String, nome: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ABRIR_AUDIOBOOK_CAMINHO, caminho)
            putExtra(MainActivity.EXTRA_ABRIR_AUDIOBOOK_NOME, nome)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.notification_conversion_done))
            .setContentText(context.getString(R.string.notification_conversion_done_message))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_conversion_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description  = context.getString(R.string.notification_conversion_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
