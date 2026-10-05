package com.jonjonesbr.audiobookgen.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueRepository
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.domain.AjustesDaConversao
import com.jonjonesbr.audiobookgen.util.TtsRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Conduz a fila de conversões a partir do próprio worker: ao terminar um item, ele é fechado e o próximo já é iniciado, sem
 * depender de a tela Conversões estar aberta (a tela só acompanha). Cancelar pelo usuário interrompe a fila: o item fica com
 * erro "cancelada" e os demais seguem pendentes, para retomar com "Iniciar".
 */
object FilaDeConversao {

    private const val PREFS = "audiobookgen_prefs"

    /** [proximo] é o item que passou a PROCESSANDO (já com o runToken novo), ou null se a fila acabou. */
    class Fechamento(val proximo: QueueStart?)

    /**
     * Fecha o item [itemId] (concluído se [erro] é null; senão com erro) e, se [avancar], marca o próximo pendente como
     * processando. Retorna null se o item não existe ou se a execução é obsoleta (o item já tem outro runToken).
     */
    fun fecharItem(
        items: MutableList<QueueItem>,
        itemId: String,
        runToken: String?,
        caminhoSaida: String?,
        erro: String?,
        avancar: Boolean,
        motorDoProximo: (QueueItem) -> String
    ): Fechamento? {
        val item = items.find { it.id == itemId } ?: return null
        if (item.runToken != null && runToken != null && item.runToken != runToken) return null
        val agora = System.currentTimeMillis()
        if (erro == null) {
            item.status = QueueStatus.CONCLUIDO
            item.progressoPct = PROGRESSO_COMPLETO
            item.erro = null
            item.caminhoSaida = caminhoSaida ?: item.caminhoSaida
            item.finalizadoEmMillis = agora
            items.removeAll { it.status == QueueStatus.CONCLUIDO }
        } else {
            item.status = QueueStatus.ERRO
            item.erro = erro
            item.finalizadoEmMillis = agora
        }
        val proximo = if (avancar) items.firstOrNull { it.status == QueueStatus.PENDENTE } else null
        val inicio = proximo?.let { ConversionQueueCoordinator(items).startNext(motorDoProximo(it)) }
        return Fechamento(inicio)
    }

    /**
     * Chamado pelo worker ao fim de cada execução que veio da fila. Toda mexida na fila acontece na thread principal, a mesma
     * da tela, para não disputar a lista.
     */
    suspend fun aoTerminarExecucao(
        context: Context,
        itemId: String?,
        runToken: String?,
        caminhoSaida: String?,
        erro: String?,
        avancar: Boolean = true
    ) {
        if (itemId == null) return
        val app = context.applicationContext
        try {
            fecharEIniciarProximo(app, itemId, runToken, caminhoSaida, erro, avancar)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FilaDeConversao", "Falha ao atualizar a fila: ${e.message}", e)
        }
    }

    private suspend fun fecharEIniciarProximo(
        app: Context,
        itemId: String,
        runToken: String?,
        caminhoSaida: String?,
        erro: String?,
        avancar: Boolean
    ) {
        withContext(Dispatchers.Main) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            QueueManager.garantirCarregada(app, prefs)
            var ajustesDoProximo: AjustesDaConversao? = null
            val fechamento = fecharItem(QueueManager.items, itemId, runToken, caminhoSaida, erro, avancar) { proximo ->
                AjustesDoLivro.resolver(app, proximo.caminhoLocal).also { ajustesDoProximo = it }.motor
            } ?: return@withContext
            QueueManager.salvar(prefs)
            val snapshot = QueueManager.items.toList()
            withContext(Dispatchers.IO) { QueueRepository.get(app).replaceAll(snapshot) }
            val inicio = fechamento.proximo
            val ajustes = ajustesDoProximo
            if (inicio != null && ajustes != null) enfileirar(app, inicio, ajustes)
        }
    }

    private fun enfileirar(context: Context, inicio: QueueStart, ajustes: AjustesDaConversao) {
        val item = inicio.item
        val parametros = ParametrosConversao(
            arquivoEntrada = item.caminhoLocal,
            voz = ajustes.voz,
            velocidade = TtsRate.fromRitmo(ajustes.ritmo),
            estilo = ESTILO_PADRAO,
            caminhoSaidaInterna = File(context.filesDir, inicio.outputFileName).absolutePath,
            motor = ajustes.motor,
            runToken = inicio.runToken,
            livroOrigemCaminho = item.caminhoLocal,
            tomMeiosTons = ajustes.tomMeiosTons,
            agudosDb = ajustes.agudosDb,
            filaItemId = item.id
        )
        val pedido = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(parametros.toInputData(AppPrefs(context).pausaMs))
            .build()
        // O worker atual ainda está rodando: o próximo entra depois dele, mesmo que ele termine com erro.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ConversionWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
    }

    private const val ESTILO_PADRAO = "padrao"
    private const val PROGRESSO_COMPLETO = 100
}
