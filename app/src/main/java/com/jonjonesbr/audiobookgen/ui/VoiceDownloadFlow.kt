package com.jonjonesbr.audiobookgen.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.tts.AndroidVoiceId
import com.jonjonesbr.audiobookgen.tts.KokoroModelManager
import com.jonjonesbr.audiobookgen.tts.OnnxModelManager
import com.jonjonesbr.audiobookgen.util.NetworkStatus
import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.util.dpToPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Verifica se a voz ONNX selecionada já está baixada e, se não, pergunta ao usuário e baixa
 * (com diálogo de progresso cancelável). Compartilhado entre MainActivity e ReaderActivity,
 * que tinham cada uma sua própria cópia de ~85 linhas quase idênticas.
 *
 * Uma instância por Activity (guarda o Job do download em andamento).
 */
class VoiceDownloadFlow(private val activity: AppCompatActivity) {

    private var downloadJob: Job? = null

    /** Pacote que a Central está baixando para o diálogo aberto (o botão Cancelar o pausa). */
    private var pacoteEmDownloadId: String? = null

    fun verificarEBaixarSeNecessario(voice: VoiceOption, onComplete: () -> Unit) {
        when {
            voice.engine == "android" && voice.precisaBaixarNoSistema ->
                mostrarDialogoBaixarVozAndroid(voice)
            voice.engine == "kokoro" && voice.id.contains("::") &&
                !com.jonjonesbr.audiobookgen.util.VoiceCatalog.modeloProntoParaVoz(voice.id, activity) ->
                mostrarDialogoConfirmarDownloadPacoteVits(voice, onComplete)
            voice.engine == "kokoro" && !voice.id.contains("::") && !KokoroModelManager.isReady(activity) ->
                mostrarDialogoConfirmarDownloadKokoro(voice, onComplete)
            voice.engine == "kokoro" -> onComplete()
            voice.engine == "pocket" &&
                !com.jonjonesbr.audiobookgen.util.VoiceCatalog.modeloProntoParaVoz(voice.id, activity) ->
                mostrarDialogoConfirmarDownloadPocket(voice, onComplete)
            voice.engine != "onnx" || OnnxModelManager(activity).isModelDownloaded(voice.id) ->
                onComplete()
            else -> mostrarDialogoConfirmarDownloadOnnx(voice, onComplete)
        }
    }
    private fun mostrarDialogoConfirmarDownloadOnnx(voice: VoiceOption, onComplete: () -> Unit) {
        val (titulo, mensagem) = if (voice.id.startsWith("supertonic-")) {
            R.string.dialog_download_supertonic_title to R.string.dialog_download_supertonic_message
        } else {
            R.string.dialog_download_voice_title to R.string.dialog_download_voice_message
        }
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.dialog_download_voice_confirm) { _, _ -> iniciarDownload(voice, onComplete) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mostrarDialogoConfirmarDownloadPacoteVits(voice: VoiceOption, onComplete: () -> Unit) {
        val pacote = com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacoteDaVoz(voice.id) ?: return
        val license = com.jonjonesbr.audiobookgen.tts.PiperVitsModelManager.models
            .firstOrNull { it.id == pacote.id }
            ?.let { com.jonjonesbr.audiobookgen.tts.PiperVitsModelManager.licenseNotice(it, activity) }
            .orEmpty()
        val mobileNotice = if (NetworkStatus.isOnWifi(activity)) ""
            else "\n\n" + activity.getString(R.string.dialog_download_piper_mobile_notice)
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_download_voice_title)
            .setMessage(activity.getString(R.string.dialog_download_piper_message, pacote.tamanhoDownloadMb, license) + mobileNotice)
            .setPositiveButton(R.string.dialog_download_voice_confirm) { _, _ -> iniciarDownload(voice, onComplete) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun mostrarDialogoConfirmarDownloadKokoro(voice: VoiceOption, onComplete: () -> Unit) {
        val mensagem = if (NetworkStatus.isOnWifi(activity)) {
            activity.getString(R.string.dialog_download_kokoro_message)
        } else {
            // Aviso de dados móveis ANTES de o usuário confirmar (~350MB).
            activity.getString(R.string.dialog_download_kokoro_message) + "\n\n" +
                activity.getString(R.string.dialog_download_kokoro_wifi_aviso)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_download_kokoro_title)
            .setMessage(mensagem)
            .setPositiveButton(R.string.dialog_download_voice_confirm) { _, _ -> iniciarDownload(voice, onComplete) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mostrarDialogoConfirmarDownloadPocket(voice: VoiceOption, onComplete: () -> Unit) {
        val pack = com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacoteDaVoz(voice.id) ?: return
        val license = activity.getString(R.string.pocket_license_notice)
        val mobileNotice = if (NetworkStatus.isOnWifi(activity)) ""
            else "\n\n" + activity.getString(R.string.pocket_mobile_data_notice)
        AlertDialog.Builder(activity)
            .setTitle(R.string.pocket_download_title)
            .setMessage(activity.getString(R.string.pocket_download_message, pack.tamanhoDownloadMb, voice.language, license) + mobileNotice)
            .setPositiveButton(R.string.dialog_download_voice_confirm) { _, _ -> iniciarDownload(voice, onComplete) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Voz conhecida pelo motor Android (ex.: Google TTS) mas cujo pacote de dados não foi
    // baixado no aparelho — não tem como o app baixar isso sozinho (é o próprio motor quem
    // controla esses arquivos), então só oferece o atalho pra tela do sistema que baixa.
    private fun mostrarDialogoBaixarVozAndroid(voice: VoiceOption) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_download_android_voice_title)
            .setMessage(R.string.dialog_download_android_voice_message)
            .setPositiveButton(R.string.btn_abrir_configuracoes) { _, _ -> abrirInstalacaoVozAndroid(voice) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun abrirInstalacaoVozAndroid(voice: VoiceOption) {
        val enginePackage = AndroidVoiceId.decode(voice.id)?.first
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            if (enginePackage != null) setPackage(enginePackage)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.toast_tts_settings_indisponivel, Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarDownload(voice: VoiceOption, onComplete: () -> Unit) {
        val progressDialog = AlertDialog.Builder(activity).create()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                activity.dpToPx(PADDING_DIALOGO_H),
                activity.dpToPx(PADDING_DIALOGO_TOPO),
                activity.dpToPx(PADDING_DIALOGO_H),
                activity.dpToPx(PADDING_DIALOGO_H)
            )
        }
        val tvStatusMsg = TextView(activity).apply {
            text = activity.getString(R.string.status_downloading_voice, 0)
            textSize = TEXT_SIZE_STATUS_SP
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 0, 0, activity.dpToPx(PADDING_TEXTO_STATUS_BAIXO))
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESSO_MAX
            progress = 0
        }
        container.addView(tvStatusMsg)
        container.addView(progressBar)

        progressDialog.apply {
            setTitle(activity.getString(R.string.dialog_downloading_title))
            setView(container)
            setCancelable(false)
            setButton(AlertDialog.BUTTON_NEGATIVE, activity.getString(android.R.string.cancel)) { _, _ ->
                // Só interrompe — NÃO apaga os arquivos parciais: o download retoma de onde parou
                // (.part). Apagar aqui jogava fora até ~270MB de progresso num cancelamento acidental.
                pacoteEmDownloadId?.let(com.jonjonesbr.audiobookgen.service.DownloadCentral::pausar)
                downloadJob?.cancel()
                Toast.makeText(activity, R.string.toast_download_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
        progressDialog.show()

        downloadJob = activity.lifecycleScope.launch {
            val result = executarDownload(voice) { pct ->
                if (!activity.isDestroyed) activity.runOnUiThread {
                    progressBar.progress = pct
                    tvStatusMsg.text = activity.getString(R.string.status_downloading_voice, pct)
                }
            }
            progressDialog.dismiss()
            pacoteEmDownloadId = null
            if (result.isSuccess) {
                Toast.makeText(activity, R.string.toast_download_completed, Toast.LENGTH_SHORT).show()
                onComplete()
            } else if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) {
                // Pausado pelo botão Cancelar (o aviso já foi mostrado ali): não é falha.
                Unit
            } else {
                val erroMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_download_failed, erroMsg),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Fronteira de download: exceção ampla intencional (KokoroModelManager.download já limpa
    // o estado parcial e relança; aqui só vira Result.failure para o diálogo reportar).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executarDownload(voice: VoiceOption, onProgress: (Int) -> Unit): Result<Unit> {
        // Todo pacote de voz baixa pela Central (fila única, pausa, notificação); só vozes sem
        // pacote registrado caem no caminho antigo.
        val pacote = com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacoteDaVoz(voice.id)
        if (pacote?.download != null) {
            return try {
                pacoteEmDownloadId = pacote.id
                com.jonjonesbr.audiobookgen.service.DownloadCentral.baixarEAguardar(
                    activity.applicationContext, pacote.id
                ) { _, pct, _, _ ->
                    onProgress((pct * PROGRESSO_MAX).toInt().coerceIn(0, PROGRESSO_MAX))
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        if (voice.engine == "kokoro" || voice.engine == "pocket") {
            return Result.failure(IllegalStateException("Download indisponível para esta voz."))
        }
        return OnnxModelManager(activity).downloadModel(voice.id, onProgress)
    }
    companion object {
        private const val PADDING_DIALOGO_H = 24
        private const val PADDING_DIALOGO_TOPO = 20
        private const val PADDING_TEXTO_STATUS_BAIXO = 16
        private const val TEXT_SIZE_STATUS_SP = 15f
        private const val PROGRESSO_MAX = 100
    }
}
