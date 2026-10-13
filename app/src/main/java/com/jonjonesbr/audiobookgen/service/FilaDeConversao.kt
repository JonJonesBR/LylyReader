package com.jonjonesbr.audiobookgen.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA
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

    /**
     * Chamado pelo worker ao iniciar uma execução da fila. Se o item foi interrompido pelo sistema (ficou PROCESSANDO ou
     * ERRO "interrompida"), volta a PROCESSANDO com o runToken do worker; só persiste se [marcarEmAndamento] mudou algo.
     */
    suspend fun aoIniciarExecucao(context: Context, itemId: String?, runToken: String?) {
        if (itemId == null) return
        val app = context.applicationContext
        try {
            withContext(Dispatchers.Main) {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                QueueManager.garantirCarregada(app, prefs)
                if (!marcarEmAndamento(QueueManager.items, itemId, runToken)) return@withContext
                QueueManager.salvar(prefs)
                val snapshot = QueueManager.items.toList()
                withContext(Dispatchers.IO) { QueueRepository.get(app).replaceAll(snapshot) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FilaDeConversao", "Falha ao iniciar item na fila: ${e.message}", e)
        }
    }

    /**
     * Retoma um item que estava em andamento, pausado pelo usuário ou interrompido pelo sistema. Devolve true só se algo
     * mudou (status, erro ou runToken). Devolve false, sem alterar nada, para item inexistente, concluído, com erro de outro
     * motivo, pendente, ou quando o item já tem outro runToken (execução de outro worker: não sobrescrever).
     */
    fun marcarEmAndamento(items: MutableList<QueueItem>, itemId: String, runToken: String?): Boolean {
        val item = items.find { it.id == itemId } ?: return false
        val retomavel = item.status == QueueStatus.PROCESSANDO ||
            item.status == QueueStatus.PAUSADO ||
            (item.status == QueueStatus.ERRO && item.erro == ERRO_FILA_INTERROMPIDA)
        if (!retomavel) return false
        if (runToken != null && item.runToken != null && item.runToken != runToken) return false
        val tokenMuda = runToken != null && item.runToken != runToken
        val mudou = item.status != QueueStatus.PROCESSANDO || item.erro != null || tokenMuda
        if (!mudou) return false
        item.status = QueueStatus.PROCESSANDO
        item.erro = null
        if (runToken != null) item.runToken = runToken
        return true
    }

    /**
     * Chamado quando a execução da fila é pausada pelo usuário (T5b). Só persiste se [pausarItem] mudou algo. Não avança
     * a fila: o item pausado fica fora de PENDENTE, então o próximo não começa sozinho.
     */
    suspend fun aoPausarExecucao(context: Context, itemId: String?, runToken: String?) {
        if (itemId == null) return
        val app = context.applicationContext
        try {
            withContext(Dispatchers.Main) {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                QueueManager.garantirCarregada(app, prefs)
                if (!pausarItem(QueueManager.items, itemId, runToken)) return@withContext
                QueueManager.salvar(prefs)
                val snapshot = QueueManager.items.toList()
                withContext(Dispatchers.IO) { QueueRepository.get(app).replaceAll(snapshot) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FilaDeConversao", "Falha ao pausar item na fila: ${e.message}", e)
        }
    }

    /**
     * Marca como PAUSADO o item que está PROCESSANDO, sem avançar a fila. Mantém o progresso e limpa o erro. Devolve true
     * só se mudou. Devolve false, sem alterar nada, se o item não existe, não está PROCESSANDO, ou se a execução é de
     * outro worker (runToken diferente nos dois lados).
     */
    fun pausarItem(items: MutableList<QueueItem>, itemId: String, runToken: String?): Boolean {
        val item = items.find { it.id == itemId } ?: return false
        if (item.status != QueueStatus.PROCESSANDO) return false
        if (item.runToken != null && runToken != null && item.runToken != runToken) return false
        item.status = QueueStatus.PAUSADO
        item.erro = null
        return true
    }

    /**
     * Chamado quando o usuário descarta uma conversão pausada que veio da fila: remove o item PAUSADO da fila e persiste.
     * Só persiste se [removerPausado] removeu algo.
     */
    suspend fun aoDescartarPausado(context: Context, itemId: String) {
        val app = context.applicationContext
        try {
            withContext(Dispatchers.Main) {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                QueueManager.garantirCarregada(app, prefs)
                if (!removerPausado(QueueManager.items, itemId)) return@withContext
                QueueManager.salvar(prefs)
                val snapshot = QueueManager.items.toList()
                withContext(Dispatchers.IO) { QueueRepository.get(app).replaceAll(snapshot) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FilaDeConversao", "Falha ao descartar item pausado na fila: ${e.message}", e)
        }
    }

    /**
     * Remove o item [itemId] da lista só se ele estiver PAUSADO. Devolve true só se removeu. Outros status e outros ids
     * não são tocados.
     */
    fun removerPausado(items: MutableList<QueueItem>, itemId: String): Boolean =
        items.removeAll { it.id == itemId && it.status == QueueStatus.PAUSADO }

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
