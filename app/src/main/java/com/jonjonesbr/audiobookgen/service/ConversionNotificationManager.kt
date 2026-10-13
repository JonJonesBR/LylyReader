package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.MainActivity
import android.app.Notification
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
        // IDs exclusivos da conversão: 1002 é da leitura guiada, 1003 do pré-render e 1004 do download/vídeo.
        const val NOTIF_ID    = 1005
        /** ID próprio da notificação de concluída: o WorkManager apaga a [NOTIF_ID] ao fim do primeiro plano. */
        const val NOTIF_ID_CONCLUIDA = 1006
        /** ID da notificação de conversão pausada (não ongoing; some quando o usuário toca nela). */
        const val NOTIF_ID_PAUSADA = 1007

        /** Percentual máximo da barra de progresso da notificação. */
        private const val PROGRESSO_MAX = 100

        /** requestCodes distintos para não colidir com o contentIntent (0) nem entre os botões. */
        private const val REQUEST_PAUSAR = 1
        private const val REQUEST_CANCELAR = 2
    }

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { criarCanal() }

    fun mostrar(progresso: Int, mensagem: String) {
        nm.notify(NOTIF_ID, construir(progresso, mensagem))
    }

    /** Notificação de andamento da conversão (também usada como primeiro plano do WorkManager). */
    fun construir(progresso: Int, mensagem: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.notification_conversion_progress))
            .setContentText(mensagem)
            .setProgress(PROGRESSO_MAX, progresso, progresso == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intentParaConversoes())
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.dl_central_pausar),
                intentPausar()
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.btn_cancelar),
                intentCancelarNaConversoes()
            )
            .build()
    }

    fun cancelar() = nm.cancel(NOTIF_ID)

    /** Botão "Pausar": broadcast para [AcaoDaConversaoReceiver], que pede a pausa ao controle da conversão. */
    private fun intentPausar(): PendingIntent {
        val intent = Intent(context, AcaoDaConversaoReceiver::class.java).apply {
            action = AcaoDaConversaoReceiver.ACAO_PAUSAR
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_PAUSAR, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Botão "Cancelar": abre [com.jonjonesbr.audiobookgen.ui.ConversoesActivity] já com a confirmação de cancelamento. */
    private fun intentCancelarNaConversoes(): PendingIntent {
        val intent = Intent(context, com.jonjonesbr.audiobookgen.ui.ConversoesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.jonjonesbr.audiobookgen.ui.ConversoesActivity.EXTRA_CONFIRMAR_CANCELAR, true)
        }
        return PendingIntent.getActivity(
            context, REQUEST_CANCELAR, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Conversão pausada: some ao toque e abre a tela de conversões, onde se retoma. */
    fun mostrarPausada(titulo: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(context.getString(R.string.notification_conversion_paused))
            .setContentText(context.getString(R.string.notification_conversion_paused_message, titulo))
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intentParaConversoes())
            .build()
        nm.notify(NOTIF_ID_PAUSADA, notification)
    }

    fun cancelarPausada() = nm.cancel(NOTIF_ID_PAUSADA)

    private fun intentParaConversoes(): PendingIntent {
        val intent = Intent(context, com.jonjonesbr.audiobookgen.ui.ConversoesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

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
        nm.notify(NOTIF_ID_CONCLUIDA, notification)
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
