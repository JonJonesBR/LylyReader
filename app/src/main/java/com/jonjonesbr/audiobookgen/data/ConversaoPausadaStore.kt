package com.jonjonesbr.audiobookgen.data

import android.content.Context
import androidx.work.Data
import com.jonjonesbr.audiobookgen.domain.CodificadorDeDadosDeTrabalho
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import org.json.JSONObject
import java.io.File

/**
 * Guarda a conversão pausada (uma por vez) em `filesDir/conversao_pausada.json`: o [Data] exato de entrada do worker,
 * para retomar com o mesmo hash de job, e os metadados para a UI. Nunca lança exceção.
 */
object ConversaoPausadaStore {

    private const val ARQUIVO = "conversao_pausada.json"

    data class Pausada(
        val data: Data,
        val titulo: String,
        val pct: Int,
        val filaItemId: String?,
        val runToken: String?,
        val instante: Long,
        /** Hash do job no Python (para apagá-lo ao descartar); null se a pausa veio antes de o Python começar. */
        val job: String? = null
    )

    /** Devolve true só se gravou; false faz quem chamou tratar a pausa como falha (o job não pode ser retomado). */
    fun salvar(context: Context, data: Data, titulo: String, pct: Int, job: String? = null): Boolean {
        try {
            val obj = JSONObject()
                .put("titulo", titulo)
                .put("pct", pct)
                .put("instante", System.currentTimeMillis())
                .put("dados", CodificadorDeDadosDeTrabalho.paraObjeto(
                    CodificadorDeDadosDeTrabalho.semChavesDeSaida(data.keyValueMap)
                ))
            job?.let { obj.put("job", it) }
            data.getString(ConversionWorker.KEY_FILA_ITEM_ID)?.let { obj.put("filaItemId", it) }
            (data.getString(ConversionWorker.KEY_RUN_TOKEN_ENTRADA) ?: data.getString(ConversionWorker.KEY_RUN_TOKEN))
                ?.let { obj.put("runToken", it) }

            val destino = arquivo(context)
            val temporario = File(destino.parentFile, "$ARQUIVO.tmp")
            temporario.writeText(obj.toString(), Charsets.UTF_8)
            if (!temporario.renameTo(destino)) {
                destino.delete()
                if (!temporario.renameTo(destino)) return false
            }
            return true
        } catch (e: Exception) {
            android.util.Log.w("ConversaoPausadaStore", "Falha ao salvar conversão pausada: ${e.message}", e)
            return false
        }
    }

    /** Conversão pausada salva, ou null se não houver ou se o arquivo estiver ilegível. */
    fun carregar(context: Context): Pausada? = try {
        val arquivo = arquivo(context)
        if (!arquivo.isFile) {
            null
        } else {
            val obj = JSONObject(arquivo.readText(Charsets.UTF_8))
            val dados = CodificadorDeDadosDeTrabalho.deObjeto(obj.getJSONObject("dados"))
            val builder = Data.Builder()
            dados.forEach { (chave, valor) ->
                when (valor) {
                    is String -> builder.putString(chave, valor)
                    is Int -> builder.putInt(chave, valor)
                    is Long -> builder.putLong(chave, valor)
                    is Boolean -> builder.putBoolean(chave, valor)
                    is Float -> builder.putFloat(chave, valor)
                    is Double -> builder.putDouble(chave, valor)
                }
            }
            Pausada(
                data = builder.build(),
                titulo = obj.getString("titulo"),
                pct = obj.getInt("pct"),
                filaItemId = if (obj.has("filaItemId")) obj.getString("filaItemId") else null,
                runToken = if (obj.has("runToken")) obj.getString("runToken") else null,
                instante = obj.getLong("instante"),
                job = if (obj.has("job")) obj.getString("job") else null
            )
        }
    } catch (e: Exception) {
        null
    }

    fun limpar(context: Context) {
        try {
            arquivo(context).delete()
        } catch (e: Exception) {
            android.util.Log.w("ConversaoPausadaStore", "Falha ao limpar conversão pausada: ${e.message}", e)
        }
    }

    fun existe(context: Context): Boolean = try {
        arquivo(context).isFile
    } catch (e: Exception) {
        false
    }

    private fun arquivo(context: Context): File = File(context.filesDir, ARQUIVO)
}
