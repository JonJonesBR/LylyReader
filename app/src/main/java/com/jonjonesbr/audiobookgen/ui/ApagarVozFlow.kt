package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.DownloadCentral
import com.jonjonesbr.audiobookgen.tts.PocketCustomVoices
import com.jonjonesbr.audiobookgen.util.PacoteVozes
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Apagar uma voz baixada direto do seletor de vozes. Apagar é apagar o PACOTE da voz (o mesmo da Central de
 * downloads): nos pacotes com várias vozes (Kokoro, Supertonic) todas saem juntas, e a confirmação diz isso.
 */
object ApagarVozFlow {
    private const val TAG = "ApagarVozFlow"
    private val MOTORES_LOCAIS = setOf("onnx", "kokoro", "pocket")

    /**
     * Pacote que [voz] ocupa no aparelho e pode ser apagado; null para vozes de nuvem, do sistema ou ainda não
     * baixadas. [pronto] é a prontidão já calculada pelo seletor (evita ler o disco a cada linha).
     */
    fun pacoteApagavel(voz: VoiceOption, pronto: Boolean): PacoteVozes? {
        val pacote = if (voz.engine in MOTORES_LOCAIS) VoiceCatalog.pacoteDaVoz(voz.id) else null
        // Voz clonada ou importada vale mesmo "não pronta" (ex.: falta o codificador): é do usuário e se apaga.
        return pacote?.takeIf { pronto || it.download == null || PocketCustomVoices.isCustom(voz.id) }
    }

    /** Pede confirmação explicando o que sai do aparelho; [aoConfirmar] apaga. */
    suspend fun confirmar(
        context: Context,
        voz: VoiceOption,
        pacote: PacoteVozes,
        vozEmUsoId: String?,
        aoConfirmar: () -> Unit
    ) {
        val nome = voz.nomeExibido(context)
        // Percorre a pasta inteira do pacote: fora da thread principal.
        val bytes = withContext(Dispatchers.IO) { pacote.tamanhoOcupadoBytes(context) }
        val tamanho = Formatter.formatShortFileSize(context, bytes)
        val outras = pacote.vozes.count { it.id != voz.id }
        val mensagem = StringBuilder(
            when {
                PocketCustomVoices.isCustom(voz.id) -> context.getString(R.string.voz_apagar_msg_clonada, nome)
                outras > 0 -> context.resources.getQuantityString(
                    R.plurals.voz_apagar_msg_pacote, outras,
                    nome, nomeExibidoDoPacote(context, pacote.id, pacote.nomeExibicao), outras, tamanho
                )
                else -> context.getString(R.string.voz_apagar_msg, nome, tamanho)
            }
        )
        if (pacote.vozes.any { it.id == vozEmUsoId }) {
            mensagem.append("\n\n").append(context.getString(R.string.voz_apagar_em_uso))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.voz_apagar_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.dl_central_apagar) { _, _ -> aoConfirmar() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Apaga o pacote do disco e atualiza a Central de downloads. Devolve false se falhou. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun apagar(context: Context, pacote: PacoteVozes): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            pacote.delete(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao apagar o pacote ${pacote.id}", e)
            false
        }
        DownloadCentral.atualizar(context)
        ok
    }
}
