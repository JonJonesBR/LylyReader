package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.ConversaoPausadaStore
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.domain.GerandoAgora
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import com.jonjonesbr.audiobookgen.service.QueueManager
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Liga o cartão "Gerando agora" ([R.layout.view_cartao_gerando_agora]) ao andamento real: a conversão do
 * WorkManager, a conversão pausada guardada em disco e a fila. Tocar nele abre a tela Conversões.
 */
object CartaoGerandoAgora {

    fun vincular(activity: AppCompatActivity) {
        val cartao = activity.findViewById<View>(R.id.cartaoGerandoAgora) ?: return
        cartao.marcarComoBotao()
        cartao.setOnClickListener { activity.startActivity(Intent(activity, ConversoesActivity::class.java)) }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(activity.applicationContext)
                    .getWorkInfosForUniqueWorkFlow(ConversionWorker.WORK_NAME)
                    .filterNotNull()
                    .collect { infos -> mostrar(activity, cartao, estadoDe(activity, infos)) }
            }
        }
    }

    private suspend fun estadoDe(activity: AppCompatActivity, infos: List<WorkInfo>): GerandoAgora {
        val ativo = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
        val app = activity.applicationContext
        val (pausada, pendentes) = withContext(Dispatchers.IO) {
            val guardada = ConversaoPausadaStore.carregar(app)?.let { it.titulo to it.pct }
            val prefs = app.getSharedPreferences("audiobookgen_prefs", android.content.Context.MODE_PRIVATE)
            QueueManager.garantirCarregada(app, prefs)
            guardada to QueueManager.items.count { it.status == QueueStatus.PENDENTE }
        }
        return GerandoAgora.de(
            rodando = ativo != null,
            nomeRodando = ativo?.progress?.getString(ConversionWorker.KEY_PROGRESS_NOME),
            pctRodando = ativo?.progress?.getInt(ConversionWorker.KEY_PROGRESS_PCT, 0) ?: 0,
            pausada = pausada,
            pendentesNaFila = pendentes
        )
    }

    private fun mostrar(activity: AppCompatActivity, cartao: View, estado: GerandoAgora) {
        val rotulo = cartao.findViewById<TextView>(R.id.tvGerandoAgoraRotulo)
        val texto = cartao.findViewById<TextView>(R.id.tvGerandoAgoraTexto)
        val barra = cartao.findViewById<ProgressBar>(R.id.pbGerandoAgora)
        cartao.visibility = if (estado == GerandoAgora.Nada) View.GONE else View.VISIBLE
        when (estado) {
            is GerandoAgora.Gerando -> {
                rotulo.text = activity.getString(R.string.gerando_agora_rotulo, estado.pct)
                texto.text = estado.nome ?: activity.getString(R.string.gerando_agora_sem_nome)
                barra.visibility = View.VISIBLE
                barra.progress = estado.pct
            }
            is GerandoAgora.Pausada -> {
                rotulo.text = activity.getString(R.string.gerando_agora_pausada_rotulo, estado.pct)
                texto.text = estado.nome
                barra.visibility = View.VISIBLE
                barra.progress = estado.pct
            }
            is GerandoAgora.NaFila -> {
                rotulo.setText(R.string.gerando_agora_fila_rotulo)
                texto.text = activity.resources.getQuantityString(
                    R.plurals.gerando_agora_fila_texto, estado.quantidade, estado.quantidade
                )
                barra.visibility = View.GONE
            }
            GerandoAgora.Nada -> Unit
        }
        cartao.contentDescription = "${rotulo.text}. ${texto.text}"
    }
}
