package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Arquivo escolhido de um item do Internet Archive: endereço de download e extensão ("epub" ou "txt"). */
data class ArquivoArchive(val url: String, val extensao: String)

/**
 * Busca no Internet Archive (API oficial `advancedsearch`). O catálogo é enviado pelos próprios usuários,
 * então a licença declarada não basta: a busca exige a marca de domínio público/CC0 E publicação até 1928
 * (domínio público nos EUA, mesma base do Gutenberg), e só traz itens com EPUB.
 */
object CatalogoArchive {
    const val POR_PAGINA = 20
    /** EPUBs de digitalização por imagem passam de dezenas de MB; acima disto cai para o texto. */
    const val EPUB_MAX_BYTES = 40L * 1024L * 1024L
    private const val BASE = "https://archive.org"
    private const val ANO_LIMITE = "1928-12-31"
    private val licencas = listOf(
        "https://creativecommons.org/publicdomain/mark/1.0/",
        "https://creativecommons.org/publicdomain/zero/1.0/"
    )

    private fun codigoIdioma(idioma: IdiomaBusca): String? = when (idioma) {
        IdiomaBusca.PT -> "por"
        IdiomaBusca.EN -> "eng"
        IdiomaBusca.ES -> "spa"
        IdiomaBusca.TODOS -> null
    }

    /** Deixa só letras, números e espaços: caracteres de sintaxe da busca (`:`, `"`, `(`…) não passam. */
    fun limparTermos(consulta: String): String =
        consulta.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex("""\s+"""), " ")

    fun montarConsulta(consulta: String, idioma: IdiomaBusca): String {
        val partes = mutableListOf(
            "mediatype:texts",
            "format:EPUB",
            licencas.joinToString(" OR ", prefix = "(", postfix = ")") { "licenseurl:\"$it\"" },
            "date:[* TO $ANO_LIMITE]"
        )
        codigoIdioma(idioma)?.let { partes += "language:$it" }
        limparTermos(consulta).takeIf { it.isNotEmpty() }?.let { partes += "(title:($it) OR creator:($it))" }
        return partes.joinToString(" AND ")
    }

    /** [pagina] começa em 1. Ordena pelos mais baixados. */
    fun urlBusca(consulta: String, idioma: IdiomaBusca, pagina: Int = 1): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$BASE/advancedsearch.php?q=${enc(montarConsulta(consulta, idioma))}" +
            listOf("identifier", "title", "creator", "date", "language")
                .joinToString("") { "&fl%5B%5D=$it" } +
            "&sort%5B%5D=${enc("downloads desc")}&rows=$POR_PAGINA&page=$pagina&output=json"
    }

    fun interpretarBusca(json: String, pagina: Int): ResultadoBusca {
        val resposta = JSONObject(json).optJSONObject("response") ?: return ResultadoBusca(emptyList(), null)
        val docs = resposta.optJSONArray("docs") ?: JSONArray()
        val livros = buildList {
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier").trim()
                val titulo = texto(d.opt("title"))
                if (id.isEmpty() || titulo.isEmpty()) continue
                add(
                    LivroEncontrado(
                        chave = "archive-$id",
                        titulo = titulo,
                        autor = texto(d.opt("creator")),
                        idioma = texto(d.opt("language")).uppercase().ifEmpty { null },
                        urlEpub = "$BASE/metadata/$id",
                        ano = texto(d.opt("date")).take(ANO_CHARS).takeIf { it.length == ANO_CHARS },
                        capaUrl = "$BASE/services/img/$id"
                    )
                )
            }
        }
        val total = resposta.optInt("numFound", 0)
        return ResultadoBusca(livros, if (pagina * POR_PAGINA < total) pagina + 1 else null)
    }

    /** Campos do IA podem vir como texto ou como lista de textos. */
    private fun texto(valor: Any?): String = when (valor) {
        is JSONArray -> if (valor.length() > 0) valor.optString(0).trim() else ""
        null, JSONObject.NULL -> ""
        else -> valor.toString().trim()
    }

    /**
     * Do `metadata` do item, escolhe o EPUB (se não for gigante) ou, na falta, o texto OCR (`_djvu.txt`).
     * Null se não houver nenhum dos dois.
     */
    fun escolherArquivo(metadataJson: String, identificador: String): ArquivoArchive? {
        val arquivos = JSONObject(metadataJson).optJSONArray("files") ?: return null
        var epub: String? = null
        var txt: String? = null
        for (i in 0 until arquivos.length()) {
            val f = arquivos.getJSONObject(i)
            val nome = f.optString("name")
            val tamanho = f.optString("size").toLongOrNull() ?: 0L
            when (f.optString("format")) {
                "EPUB" -> if (epub == null && tamanho in 1..EPUB_MAX_BYTES) epub = nome
                "DjVuTXT" -> if (txt == null && tamanho > 0) txt = nome
            }
        }
        fun url(nome: String) = "$BASE/download/$identificador/" + nome.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return when {
            epub != null -> ArquivoArchive(url(epub), "epub")
            txt != null -> ArquivoArchive(url(txt), "txt")
            else -> null
        }
    }

    private const val ANO_CHARS = 4
}
