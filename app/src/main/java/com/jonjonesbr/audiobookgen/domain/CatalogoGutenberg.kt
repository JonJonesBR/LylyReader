package com.jonjonesbr.audiobookgen.domain

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

const val GUTENBERG_BASE = "https://www.gutenberg.org"

object CatalogoGutenberg {
    private val padraoId = Regex("""/ebooks/(\d+)\.opds""")
    private val padraoIdioma = Regex("""^(.*)\s+\(([^()]+)\)\s*$""")

    /** Endereço da busca no feed OPDS oficial. [inicio] é o `start_index` (1 = primeira página). */
    fun urlBusca(consulta: String, idioma: IdiomaBusca, inicio: Int = 1): String {
        val termos = buildList {
            consulta.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            idioma.codigo?.let { add("l.$it") }
        }.joinToString(" ")
        val q = URLEncoder.encode(termos, "UTF-8")
        val pagina = if (inicio > 1) "&start_index=$inicio" else ""
        return "$GUTENBERG_BASE/ebooks/search.opds/?query=$q&sort_order=downloads$pagina"
    }

    /** Lê uma página do feed OPDS. Ignora entradas que não são livros (ex.: "Authors"). */
    fun interpretar(xml: String): ResultadoBusca {
        val fabrica = DocumentBuilderFactory.newInstance().apply {
            // O feed é de terceiros: sem DTD/entidades externas. O parser do Android não aceita este
            // recurso (e já não resolve entidades externas), por isso a tentativa é tolerante.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isNamespaceAware = false
        }
        val doc = fabrica.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val raiz = doc.documentElement
        val livros = mutableListOf<LivroEncontrado>()
        var proxima: String? = null

        val filhos = raiz.childNodes
        for (i in 0 until filhos.length) {
            val no = filhos.item(i) as? Element ?: continue
            when (no.tagName) {
                "link" -> if (no.getAttribute("rel") == "next") proxima = no.getAttribute("href").ifBlank { null }
                "entry" -> entrada(no)?.let(livros::add)
            }
        }
        return ResultadoBusca(livros, proxima?.let(::inicioDaProxima))
    }

    private fun entrada(e: Element): LivroEncontrado? {
        var id: Int? = null
        var titulo = ""
        var autor = ""
        val filhos = e.childNodes
        for (i in 0 until filhos.length) {
            val no = filhos.item(i) as? Element ?: continue
            when (no.tagName) {
                "title" -> titulo = no.textContent.trim()
                "content" -> autor = no.textContent.trim()
                "link" -> if (no.getAttribute("rel") == "subsection") {
                    padraoId.find(no.getAttribute("href"))?.let { id = it.groupValues[1].toInt() }
                }
            }
        }
        val idLivro = id ?: return null
        if (titulo.isEmpty()) return null
        val m = padraoIdioma.find(titulo)
        return LivroEncontrado(
            chave = "gutenberg-$idLivro",
            titulo = m?.groupValues?.get(1)?.trim() ?: titulo,
            autor = autor,
            idioma = m?.groupValues?.get(2)?.trim(),
            // EPUB sem imagens: bem menor e suficiente para ler/ouvir. Endereço oficial e estável.
            urlEpub = "$GUTENBERG_BASE/ebooks/$idLivro.epub.noimages",
            capaUrl = "$GUTENBERG_BASE/cache/epub/$idLivro/pg$idLivro.cover.small.jpg"
        )
    }

    /** `start_index` da próxima página a partir do link `next` do feed (relativo). */
    internal fun inicioDaProxima(href: String): Int? =
        Regex("""[?&]start_index=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
}
