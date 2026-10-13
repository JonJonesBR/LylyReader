package com.jonjonesbr.audiobookgen.domain

import org.json.JSONObject

/**
 * Converte o conteúdo de um trabalho (chave -> valor simples) para JSON e de volta, guardando o tipo de cada valor.
 * Função pura, sem Android (só org.json). Valores nulos e tipos sem suporte (arrays etc.) são ignorados.
 */
object CodificadorDeDadosDeTrabalho {

    private const val TIPO = "t"
    private const val VALOR = "v"

    fun paraJson(mapa: Map<String, Any?>): String = paraObjeto(mapa).toString()

    /**
     * Remove as chaves de saída/progresso de workers anteriores (`result_*`, `progress_*`): em itens encadeados o WorkManager
     * mescla a saída anterior na entrada do próximo, e essas chaves não fazem parte da entrada original.
     */
    fun semChavesDeSaida(mapa: Map<String, Any?>): Map<String, Any?> =
        mapa.filterKeys { !it.startsWith("result_") && !it.startsWith("progress_") }

    /** Mapa de volta a partir do JSON; JSON inválido devolve mapa vazio. */
    fun deMapa(json: String): Map<String, Any> = try {
        deObjeto(JSONObject(json))
    } catch (e: Exception) {
        emptyMap()
    }

    fun paraObjeto(mapa: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        mapa.forEach { (chave, valor) ->
            val codificado: Pair<String, Any> = when (valor) {
                is String -> "s" to valor
                is Int -> "i" to valor
                is Long -> "l" to valor
                is Boolean -> "b" to valor
                // Float e Double vão como texto para voltar exatos (org.json não aceita NaN/Infinity em número).
                is Float -> "f" to valor.toString()
                is Double -> "d" to valor.toString()
                else -> return@forEach
            }
            obj.put(chave, JSONObject().put(TIPO, codificado.first).put(VALOR, codificado.second))
        }
        return obj
    }

    fun deObjeto(obj: JSONObject): Map<String, Any> {
        val saida = LinkedHashMap<String, Any>()
        obj.keys().forEach { chave ->
            val entrada = obj.optJSONObject(chave) ?: return@forEach
            val valor: Any? = try {
                when (entrada.optString(TIPO)) {
                    "s" -> entrada.getString(VALOR)
                    "i" -> entrada.getInt(VALOR)
                    "l" -> entrada.getLong(VALOR)
                    "b" -> entrada.getBoolean(VALOR)
                    "f" -> entrada.getString(VALOR).toFloat()
                    "d" -> entrada.getString(VALOR).toDouble()
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (valor != null) saida[chave] = valor
        }
        return saida
    }
}
