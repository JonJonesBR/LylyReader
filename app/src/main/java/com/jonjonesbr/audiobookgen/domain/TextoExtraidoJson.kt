package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * [TextoExtraido] em JSON: o formato que o Python devolve na extração (chaves longas) e o do cache de texto
 * do leitor (chaves curtas, para o arquivo ficar pequeno). Ler uma string só é muito mais rápido que ler o
 * dicionário Python campo a campo pela ponte Java↔Python.
 */
object TextoExtraidoJson {

    /** Resultado de `extrair_texto_estruturado_json`: {"ok", "erro"?, "capitulos", "paragrafos", "total_chars"}. */
    fun daExtracao(json: String, tituloSemNome: String): Result<TextoExtraido> {
        val raiz = JSONObject(json)
        if (!raiz.optBoolean("ok", false)) {
            return Result.failure(IllegalStateException(raiz.optString("erro", "")))
        }
        val capitulos = raiz.optJSONArray("capitulos") ?: JSONArray()
        val paragrafos = raiz.optJSONArray("paragrafos") ?: JSONArray()
        return Result.success(
            TextoExtraido(
                capitulos = List(capitulos.length()) { i ->
                    val c = capitulos.getJSONObject(i)
                    Capitulo(
                        titulo = c.optString("titulo").ifEmpty { tituloSemNome },
                        indiceParagrafoInicio = c.optInt("inicio"),
                        indiceParagrafoFim = c.optInt("fim")
                    )
                },
                paragrafos = List(paragrafos.length()) { i ->
                    val p = paragrafos.getJSONObject(i)
                    Paragrafo(p.optString("texto"), p.optInt("char_offset"), p.optInt("capitulo_idx"))
                },
                totalChars = raiz.optInt("total_chars")
            )
        )
    }

    /** [removidas]: no texto limpo, quantas linhas a limpeza tirou (o leitor mostra o número). */
    fun paraCache(texto: TextoExtraido, removidas: Int = 0): String = JSONObject()
        .put("c", JSONArray().apply {
            texto.capitulos.forEach {
                put(JSONArray().put(it.titulo).put(it.indiceParagrafoInicio).put(it.indiceParagrafoFim))
            }
        })
        .put("p", JSONArray().apply {
            texto.paragrafos.forEach { put(JSONArray().put(it.texto).put(it.charOffset).put(it.indiceCapitulo)) }
        })
        .put("t", texto.totalChars)
        .put("r", removidas)
        .toString()

    fun removidasDoCache(json: String): Int = JSONObject(json).optInt("r")

    fun doCache(json: String): TextoExtraido {
        val raiz = JSONObject(json)
        val c = raiz.getJSONArray("c")
        val p = raiz.getJSONArray("p")
        return TextoExtraido(
            capitulos = List(c.length()) { i ->
                val a = c.getJSONArray(i)
                Capitulo(a.getString(0), a.getInt(1), a.getInt(2))
            },
            paragrafos = List(p.length()) { i ->
                val a = p.getJSONArray(i)
                Paragrafo(a.getString(0), a.getInt(1), a.getInt(2))
            },
            totalChars = raiz.getInt("t")
        )
    }
}
