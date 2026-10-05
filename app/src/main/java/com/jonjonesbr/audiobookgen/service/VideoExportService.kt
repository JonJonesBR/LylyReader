package com.jonjonesbr.audiobookgen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.CoverArtUseCase
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.domain.VideoExportUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exporta o audiobook como vídeo (uma ou mais partes) em primeiro plano, para a exportação
 * sobreviver à tela apagada ou à troca de app. A tela só observa o [VideoExportBridge].
 */
class VideoExportService : Service() {

    companion object {
        private const val TAG = "VideoExportService"
        private const val CHANNEL_ID = "lylyreader_video_export"
        private const val NOTIF_ID = 1004
        private const val ACTION_CANCEL = "com.jonjonesbr.audiobookgen.VIDEO_EXPORT_CANCEL"
        private const val MS_POR_SEGUNDO = 1000L
        private const val BYTES_POR_SEGUNDO_AAC = 8_000L // AAC a 64 kbps
        private const val BYTES_POR_MB = 1_048_576L

        fun start(context: Context) {
            val intent = Intent(context, VideoExportService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val pedido = VideoExportBridge.pedido
        criarCanal()
        iniciarEmPrimeiroPlano(montar(VideoExportBridge.state.value ?: EstadoVideoExport(pedido?.nome.orEmpty())))
        if (pedido == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (job?.isActive != true) {
            job = scope.launch { executar(pedido) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun executar(pedido: PedidoVideo) {
        VideoExportBridge.cancelar = { job?.cancel() }
        publicar(EstadoVideoExport(pedido.nome))
        val pasta = File(cacheDir, "export_video_${System.currentTimeMillis()}").apply { mkdirs() }
        var desfecho: EstadoVideoExport
        try {
            desfecho = gerar(pedido, pasta).fold(
                onSuccess = { EstadoVideoExport(pedido.nome, partesExportadas = it) },
                onFailure = { e -> estadoDeFalha(pedido, e) }
            )
        } catch (e: CancellationException) {
            desfecho = EstadoVideoExport(pedido.nome, cancelado = true)
        } finally {
            withContext(NonCancellable) { pasta.deleteRecursively() }
        }
        VideoExportBridge.cancelar = null
        VideoExportBridge.pedido = null
        publicarFim(desfecho)
    }

    private fun estadoDeFalha(pedido: PedidoVideo, e: Throwable): EstadoVideoExport {
        if (e is CancellationException) return EstadoVideoExport(pedido.nome, cancelado = true)
        Log.e(TAG, "Erro ao exportar video: ${e.message}", e)
        return EstadoVideoExport(pedido.nome, erro = e.message ?: "")
    }

    private suspend fun gerar(pedido: PedidoVideo, pasta: File): Result<Int> {
        val audioTemp = File(pasta, "origem.mp3")
        val copiado = withContext(Dispatchers.IO) { copiarAudioParaTemp(pedido, audioTemp) }
        if (!copiado) {
            return Result.failure(IllegalStateException(getString(R.string.erro_audio_origem_indisponivel)))
        }
        val faltaMb = megabytesFaltando(pedido, pasta)
        if (faltaMb > 0) {
            return Result.failure(IllegalStateException(getString(R.string.erro_espaco_video, faltaMb)))
        }
        val capa = CoverArtUseCase(this).let { uc ->
            uc.extrairOuGerarCapa(caminhoLivro = null, titulo = pedido.nome, autor = "LylyReader")?.let { uc.carregarBitmap(it) }
        }
        val parteAtual = AtomicInteger(1)
        val partes = VideoExportUseCase().exportar(
            audioTemp, capa, pasta, pedido.opcoes,
            fronteirasCapituloUs = fronteirasDeCapitulo(pedido),
            onProgress = { pct -> publicar(EstadoVideoExport(pedido.nome, parteAtual.get(), pct)) },
            onParte = { parteAtual.set(it) }
        ).getOrElse { return Result.failure(it) }
        return salvarPartes(pedido, partes)
    }

    /** Inícios de capítulo (µs) do audiobook gerado pelo app: cortes preferidos entre as partes. */
    private fun fronteirasDeCapitulo(pedido: PedidoVideo): List<Long> =
        pedido.caminho?.let { ChapterMarksStore.carregar(this, it) }.orEmpty()
            .map { it.inicioMs * MS_POR_SEGUNDO }
            .filter { it > 0 }

    // 25% de folga sobre o AAC para o contêiner e os quadros de vídeo.
    private fun megabytesFaltando(pedido: PedidoVideo, pasta: File): Long {
        val necessario = pedido.duracaoMs / MS_POR_SEGUNDO * BYTES_POR_SEGUNDO_AAC * 5 / 4
        val falta = necessario - pasta.usableSpace
        return if (falta > 0) falta / BYTES_POR_MB + 1 else 0
    }

    private fun copiarAudioParaTemp(pedido: PedidoVideo, destino: File): Boolean = try {
        if (pedido.caminho != null) {
            File(pedido.caminho).copyTo(destino, overwrite = true)
        } else {
            val entrada = contentResolver.openInputStream(pedido.uri) ?: error("Nao foi possivel abrir o audio de origem")
            entrada.use { inp -> destino.outputStream().use { out -> inp.copyTo(out) } }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Erro ao copiar audio de origem para exportar video: ${e.message}")
        false
    }

    private suspend fun salvarPartes(pedido: PedidoVideo, partes: List<File>): Result<Int> {
        val pastaDestino = AppPrefs(this).pastaDestino?.let { Uri.parse(it) }
        val base = pedido.nome.removeSuffix(".mp3").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "audiobook" }
        partes.forEachIndexed { i, arquivo ->
            val nome = if (partes.size == 1) "$base.mp4" else "$base - Parte ${i + 1} de ${partes.size}.mp4"
            val renomeado = File(arquivo.parentFile, nome)
            val pronto = if (arquivo.renameTo(renomeado)) renomeado else arquivo
            DocumentExportUseCase(this).exportar(pronto, pastaDestino, "video/mp4")
                .onFailure { return Result.failure(it) }
        }
        return Result.success(partes.size)
    }

    private fun publicar(estado: EstadoVideoExport) {
        VideoExportBridge.state.value = estado
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, montar(estado))
    }

    private fun publicarFim(estado: EstadoVideoExport) {
        VideoExportBridge.state.value = estado
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!estado.cancelado) notificarFim(estado)
        stopSelf()
    }

    private fun iniciarEmPrimeiroPlano(notificacao: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notificacao)
        }
    }

    private fun montar(estado: EstadoVideoExport): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, com.jonjonesbr.audiobookgen.ui.LibraryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelar = PendingIntent.getService(
            this, 1, Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.video_notif_title, estado.titulo))
            .setContentText(getString(R.string.status_exportando_video_parte, estado.parte, estado.pct))
            .setProgress(100, estado.pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(android.R.string.cancel), cancelar)
            .build()
    }

    private fun notificarFim(estado: EstadoVideoExport) {
        val partes = estado.partesExportadas
        val texto = when {
            partes == 1 -> getString(R.string.toast_video_exportado)
            partes != null -> getString(R.string.toast_video_exportado_partes, partes)
            else -> getString(R.string.toast_erro_exportar_video, estado.erro.orEmpty())
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.video_notif_title, estado.titulo))
            .setContentText(texto)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.video_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }
}
