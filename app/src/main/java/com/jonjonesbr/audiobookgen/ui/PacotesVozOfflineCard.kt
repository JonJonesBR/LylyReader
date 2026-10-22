package com.jonjonesbr.audiobookgen.ui

import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card "Vozes offline" de [SettingsActivity] (extraído da Activity, padrão de [ReaderRetornoController]):
 * UMA linha por pacote registrado. Baixar, apagar e importar (BYOM) ficam aqui. O seletor de arquivo
 * BYOM continua registrado na Activity; ela o abre pelo callback [abrirSeletorByom] e devolve o
 * arquivo escolhido por [importarPacoteByom].
 */
class PacotesVozOfflineCard(
    private val activity: AppCompatActivity,
    private val abrirSeletorByom: () -> Unit,
) {
    private val dialogos = DialogosAbertos(activity)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)

    // Diálogos do sistema (android.app) de baixar pacote: fechados ao sair da tela (evita WindowLeaked).
    private val dialogosNativos = mutableListOf<android.app.Dialog>()

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialogosNativos.forEach { if (it.isShowing) it.dismiss() }
                dialogosNativos.clear()
            }
        })
    }

    /**
     * Card "Vozes offline" generalizado (V6 T2.2): UMA linha por pacote registrado
     * (VoiceCatalog.pacotesRegistrados — Supertonic, Kokoro; Parte A/B entram sozinhas).
     * Linha baixada → tamanho ocupado + Apagar; não baixada → ~tamanho + Baixar (diálogo de
     * progresso; aviso de dados móveis fora de WiFi via NetworkStatus, fonte única).
     */
    fun carregar(container: LinearLayout) {
        container.removeAllViews()
        activity.lifecycleScope.launch {
            val pacotes = withContext(Dispatchers.IO) { com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacotesRegistrados() }
            if (pacotes.isEmpty()) return@launch
            val linhas = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacotesVisiveis(activity.applicationContext).map { pacote ->
                    Triple(
                        pacote,
                        pacote.isPronto(activity.applicationContext),
                        pacote.tamanhoOcupadoBytes(activity.applicationContext).toDouble() / BYTES_IN_MB
                    )
                }
            }
            for ((pacote, pronto, ocupadoMb) in linhas) {
                container.addView(montarLinhaPacote(pacote, pronto, ocupadoMb))
            }
        }
    }

    /** Recarrega o card no próprio container (mesmo view que a Activity usa em onResume). */
    private fun recarregar() {
        carregar(activity.findViewById<LinearLayout>(R.id.layoutPacotesOffline))
    }

    private fun montarLinhaPacote(
        pacote: com.jonjonesbr.audiobookgen.util.PacoteVozes,
        pronto: Boolean,
        ocupadoMb: Double
    ): android.view.View {
        val linha = activity.layoutInflater.inflate(R.layout.item_pacote_offline, null)
        linha.findViewById<android.widget.TextView>(R.id.tvPacoteNome).text = nomeExibidoDoPacote(activity, pacote.id, pacote.nomeExibicao)
        val estado = linha.findViewById<android.widget.TextView>(R.id.tvPacoteEstado)
        val botao = linha.findViewById<android.widget.Button>(R.id.btnPacoteAcao)
        if (pronto) {
            estado.text = activity.getString(
                R.string.pacote_offline_baixado_tamanho,
                ocupadoMb,
                activity.getString(R.string.pacote_offline_estado_baixado)
            )
            botao.text = activity.getString(R.string.pacote_offline_apagar)
            botao.backgroundTintList =
                androidx.core.content.ContextCompat.getColorStateList(activity, R.color.color_surface_variant)
            botao.setTextColor(
                androidx.core.content.ContextCompat.getColor(activity, R.color.color_error)
            )
            botao.setOnClickListener { apagarPacote(pacote) }
        } else {
            estado.text = activity.getString(R.string.pacote_offline_nao_baixado, pacote.tamanhoDownloadMb)
            botao.text = activity.getString(R.string.pacote_offline_baixar)
            botao.setOnClickListener { baixarPacote(pacote) }
        }
        return linha
    }

    private fun apagarPacote(pacote: com.jonjonesbr.audiobookgen.util.PacoteVozes) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    pacote.delete(activity.applicationContext)
                    true
                } catch (e: Exception) {
                    Log.e("SettingsActivity", "Erro ao apagar pacote ${pacote.id}", e)
                    false
                }
            }
            if (ok) {
                Toast.makeText(
                    activity, R.string.toast_vozes_apagadas, Toast.LENGTH_SHORT
                ).show()
            } else {
                activity.mostrarErro(R.string.toast_erro_apagar_vozes)
            }
            recarregar()
        }
    }

    /**
     * "Importar voz" do card Vozes offline (BYOM, V6 Parte B): antes de abrir o seletor,
     * avisa que a responsabilidade pela licença do modelo importado é do usuário (o app não
     * verifica proveniência) — plano exige esse aviso explícito no fluxo de importação.
     */
    fun mostrarAvisoLicencaEImportar() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_byom_licenca_titulo)
            .setMessage(R.string.dialog_byom_licenca_mensagem)
            .setPositiveButton(R.string.btn_continuar) { _, _ ->
                try {
                    abrirSeletorByom()
                } catch (e: android.content.ActivityNotFoundException) {
                    Log.w("SettingsActivity", "Erro ao iniciar importação de voz BYOM", e)
                    Toast.makeText(activity, R.string.toast_erro_importar_voz, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    fun importarPacoteByom(uri: Uri) {
        Toast.makeText(activity, R.string.toast_importando_voz, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val resultado = com.jonjonesbr.audiobookgen.tts.BYOMManager.importar(activity.applicationContext, uri)
            resultado.onSuccess { nomePacote ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_voz_importada, nomePacote),
                    Toast.LENGTH_LONG
                ).show()
                recarregar()
            }.onFailure { e ->
                Log.w("SettingsActivity", "Erro ao importar pacote BYOM", e)
                activity.mostrarErro(activity.getString(R.string.toast_erro_importar_voz_motivo, activity.getString(mensagemDeErro(e))))
            }
        }
    }

    /**
     * Baixar de um pacote do card: fora de WiFi avisa ANTES (mesma checagem de conectividade
     * do fluxo de seleção de voz — NetworkStatus, fonte única; V6 item 2).
     */
    private fun baixarPacote(pacote: com.jonjonesbr.audiobookgen.util.PacoteVozes) {
        if (!com.jonjonesbr.audiobookgen.util.NetworkStatus.isOnWifi(activity)) {
            val aviso = android.app.AlertDialog.Builder(activity)
                .setTitle(nomeExibidoDoPacote(activity, pacote.id, pacote.nomeExibicao))
                .setMessage(R.string.pacote_download_wifi_aviso)
                .setPositiveButton(R.string.pacote_offline_baixar) { _, _ -> baixarPacoteDireto(pacote) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialogosNativos.add(aviso)
            aviso.show()
        } else {
            baixarPacoteDireto(pacote)
        }
    }

    // Fronteira de download: exceção ampla intencional (o download limpa o parcial e relança;
    // aqui vira toast de falha + refresh).
    @Suppress("TooGenericExceptionCaught")
    private fun baixarPacoteDireto(pacote: com.jonjonesbr.audiobookgen.util.PacoteVozes) {
        if (pacote.download == null) return
        val progressDialog = android.app.AlertDialog.Builder(activity).create()
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 40, 48, 24)
        }
        val tvStatus = android.widget.TextView(activity).apply {
            textSize = 14f
        }
        val barra = android.widget.ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply { max = 100 }
        container.addView(tvStatus)
        container.addView(barra)
        progressDialog.apply {
            setTitle(activity.getString(R.string.dialog_downloading_title))
            setView(container)
            setCancelable(false)
            setButton(
                android.app.AlertDialog.BUTTON_NEGATIVE,
                activity.getString(android.R.string.cancel)
            ) { _, _ ->
                // Pausa pela Central: o parcial (.part) fica e dá para continuar de onde parou.
                com.jonjonesbr.audiobookgen.service.DownloadCentral.pausar(pacote.id)
            }
        }
        dialogosNativos.add(progressDialog)
        progressDialog.show()
        activity.lifecycleScope.launch {
            try {
                com.jonjonesbr.audiobookgen.service.DownloadCentral.baixarEAguardar(
                    activity.applicationContext, pacote.id
                ) { _, pct, _, _ ->
                    if (!activity.isDestroyed) activity.runOnUiThread {
                        barra.progress = (pct * 100).toInt().coerceIn(0, 100)
                    }
                }
                progressDialog.dismiss()
                Toast.makeText(
                    activity, R.string.toast_download_completed, Toast.LENGTH_SHORT
                ).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                progressDialog.dismiss()
                // Tela fechada/girada não cancela o download (segue na Central); só o botão Cancelar pausa.
                if (!activity.isDestroyed) {
                    Toast.makeText(activity, R.string.toast_download_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.w("SettingsActivity", "Falha ao baixar voz", e)
                activity.mostrarErro(activity.getString(R.string.toast_download_failed, activity.getString(mensagemDeErro(e))))
            }
            recarregar()
        }
    }

    private companion object {
        private const val BYTES_IN_MB = 1048576.0
    }
}
