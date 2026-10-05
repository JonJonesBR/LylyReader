package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueRepository
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun novoRunToken(): String = UUID.randomUUID().toString()

object QueueManager {

    const val PREF_KEY = "fila_processamento"
    private const val MAX_HISTORY_ITEMS = 50

    private const val PROGRESSO_PCT_MAXIMO = 100

    val items: MutableList<QueueItem> = mutableListOf()

    fun adicionar(nome: String, caminhoLocal: String, motor: String? = null): QueueItem {
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            nome = nome,
            caminhoLocal = caminhoLocal,
            motor = motor,
        )
        items.add(item)
        return item
    }

    fun remover(id: String) {
        items.removeAll { it.id == id }
    }

    fun mover(de: Int, para: Int) {
        if (de < 0 || de >= items.size || para < 0 || para >= items.size) return
        val item = items.removeAt(de)
        items.add(para, item)
    }

    fun proximoPendente(): QueueItem? =
        items.firstOrNull { it.status == QueueStatus.PENDENTE }

    fun marcarProcessando(id: String, motor: String? = null) {
        items.find { it.id == id }?.let {
            it.status = QueueStatus.PROCESSANDO
            it.motor = motor ?: it.motor
            it.progressoPct = 0
            it.erro = null
            it.iniciadoEmMillis = System.currentTimeMillis()
            it.finalizadoEmMillis = null
            it.runToken = novoRunToken()
        }
    }

    fun atualizarProgresso(id: String, progressoPct: Int, runToken: String? = null) {
        items.find { it.id == id }?.let {
            if (runToken != null && runToken != it.runToken) return
            it.progressoPct = progressoPct.coerceIn(0, PROGRESSO_PCT_MAXIMO)
        }
    }

    fun marcarConcluido(id: String, caminhoSaida: String? = null, runToken: String? = null) {
        items.find { it.id == id }?.let {
            if (runToken != null && runToken != it.runToken) return
            it.status = QueueStatus.CONCLUIDO
            it.progressoPct = PROGRESSO_PCT_MAXIMO
            it.erro = null
            it.caminhoSaida = caminhoSaida ?: it.caminhoSaida
            it.finalizadoEmMillis = System.currentTimeMillis()
        }
    }

    fun marcarErro(id: String, erro: String, runToken: String? = null) {
        items.find { it.id == id }?.let {
            if (runToken != null && runToken != it.runToken) return
            it.status = QueueStatus.ERRO
            it.erro = erro
            it.finalizadoEmMillis = System.currentTimeMillis()
        }
    }

    fun repetir(id: String) {
        items.find { it.id == id }?.let {
            it.status = QueueStatus.PENDENTE
            it.erro = null
            it.progressoPct = 0
            it.iniciadoEmMillis = null
            it.finalizadoEmMillis = null
            it.caminhoSaida = null
            it.runToken = null
        }
    }

    fun contarPendentes(): Int = items.count { it.status == QueueStatus.PENDENTE }

    fun contarAtivos(): Int =
        items.count { it.status == QueueStatus.PENDENTE || it.status == QueueStatus.PROCESSANDO }

    fun resumo(): QueueSummary = QueueSummary(
        total = items.size,
        pendentes = items.count { it.status == QueueStatus.PENDENTE },
        processando = items.count { it.status == QueueStatus.PROCESSANDO },
        concluidos = items.count { it.status == QueueStatus.CONCLUIDO },
        erros = items.count { it.status == QueueStatus.ERRO },
    )

    fun limparNaoAtivos() {
        items.removeAll { it.status == QueueStatus.CONCLUIDO || it.status == QueueStatus.ERRO }
    }

    fun salvar(prefs: SharedPreferences) {
        prefs.edit().putString(PREF_KEY, toJson()).apply()
    }

    /**
     * Persiste a fila atual nas SharedPreferences (síncrono) e replica para o Room em background
     * (assíncrono). Estava duplicado (mesma lógica) em MainActivity e QueueActivity.
     */
    fun persistir(scope: CoroutineScope, context: Context, prefs: SharedPreferences) {
        salvar(prefs)
        val snapshot = items.toList()
        scope.launch(Dispatchers.IO) {
            QueueRepository.get(context).replaceAll(snapshot)
        }
    }

    fun carregar(prefs: SharedPreferences) {
        val json = prefs.getString(PREF_KEY, null) ?: return
        carregarJson(json)
    }

    /** Carrega a fila persistida se a memória estiver vazia: mexer na fila antes disso e persistir apagaria a que está salva. */
    suspend fun garantirCarregada(context: Context, prefs: SharedPreferences) {
        if (items.isNotEmpty()) return
        val salvos = kotlinx.coroutines.withContext(Dispatchers.IO) { QueueRepository.get(context).loadItems(prefs) }
        if (items.isEmpty()) replaceAll(salvos)
    }

    fun replaceAll(newItems: List<QueueItem>) {
        items.clear()
        items.addAll(newItems.takeLast(MAX_HISTORY_ITEMS))
    }

    fun toJson(): String {
        val arr = JSONArray()
        items
            .takeLast(MAX_HISTORY_ITEMS)
            .forEach { item -> arr.put(item.toJson()) }
        return arr.toString()
    }

    fun carregarJson(json: String) {
        replaceAll(itemsFromJson(json))
    }

    fun itemsFromJson(json: String?): List<QueueItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val restored = mutableListOf<QueueItem>()
            val existingIds = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id  = obj.getString("id")
                if (existingIds.add(id)) {
                    restored.add(obj.toQueueItem(id))
                }
            }
            restored
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun QueueItem.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nome", nome)
        put("caminhoLocal", caminhoLocal)
        put("status", status.name)
        put("erro", erro)
        put("motor", motor)
        put("progressoPct", progressoPct)
        put("criadoEmMillis", criadoEmMillis)
        put("iniciadoEmMillis", iniciadoEmMillis)
        put("finalizadoEmMillis", finalizadoEmMillis)
        put("caminhoSaida", caminhoSaida)
    }

    private fun JSONObject.toQueueItem(id: String): QueueItem {
        val restoredStatus = runCatching {
            QueueStatus.valueOf(optString("status", QueueStatus.PENDENTE.name))
        }.getOrDefault(QueueStatus.PENDENTE)
        val status = if (restoredStatus == QueueStatus.PROCESSANDO) QueueStatus.ERRO else restoredStatus

        return QueueItem(
            id = id,
            nome = optString("nome"),
            caminhoLocal = optString("caminhoLocal"),
            status = status,
            erro = optionalString("erro")
                ?: if (restoredStatus == QueueStatus.PROCESSANDO) {
                    com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA
                } else {
                    null
                },
            motor = optionalString("motor"),
            progressoPct = optInt("progressoPct", if (status == QueueStatus.CONCLUIDO) PROGRESSO_PCT_MAXIMO else 0),
            criadoEmMillis = optLong("criadoEmMillis", System.currentTimeMillis()),
            iniciadoEmMillis = optionalLong("iniciadoEmMillis"),
            finalizadoEmMillis = optionalLong("finalizadoEmMillis"),
            caminhoSaida = optionalString("caminhoSaida"),
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optionalLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}

data class QueueSummary(
    val total: Int,
    val pendentes: Int,
    val processando: Int,
    val concluidos: Int,
    val erros: Int,
)
