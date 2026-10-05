package com.jonjonesbr.audiobookgen.domain

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Busca no Wikisource (pt/en/es) pela API oficial do MediaWiki; o EPUB sai do `ws-export`, ferramenta
 * da própria Wikimedia que monta o livro a partir das páginas da obra. Textos em domínio público ou
 * com licença livre (ver a página de cada obra).
 */
object CatalogoWikisource {
    const val POR_PAGINA = 20
    private const val EXPORT_BASE = "https://ws-export.wmcloud.org/"

    /** O Wikisource é um site por idioma: "todos" não existe, então cai no português. */
    fun idiomaEfetivo(idioma: IdiomaBusca): String = idioma.codigo ?: IdiomaBusca.PT.codigo!!

    fun urlBusca(consulta: String, idioma: IdiomaBusca, deslocamento: Int = 0): String {
        val lang = idiomaEfetivo(idioma)
        val q = URLEncoder.encode(consulta.trim(), "UTF-8")
        // ns=0: só a página principal das obras; sem snippet (resposta leve).
        return "https://$lang.wikisource.org/w/api.php?action=query&list=search&srsearch=$q" +
            "&srnamespace=0&srlimit=$POR_PAGINA&sroffset=$deslocamento&srprop=&format=json"
    }

    fun urlEpub(titulo: String, idioma: IdiomaBusca): String =
        "${EXPORT_BASE}?format=epub&lang=${idiomaEfetivo(idioma)}&page=${URLEncoder.encode(titulo.replace(' ', '_'), "UTF-8")}"

    /** Subpáginas ("Dom Casmurro/I") são capítulos: some com elas, o EPUB da obra já as inclui. */
    fun interpretar(json: String, idioma: IdiomaBusca): ResultadoBusca {
        val raiz = JSONObject(json)
        val busca = raiz.optJSONObject("query")?.optJSONArray("search")
        val lang = idiomaEfetivo(idioma)
        val livros = buildList {
            if (busca != null) for (i in 0 until busca.length()) {
                val titulo = busca.getJSONObject(i).optString("title").trim()
                if (titulo.isEmpty() || '/' in titulo) continue
                add(
                    LivroEncontrado(
                        chave = "wikisource-$lang-${Integer.toHexString(titulo.hashCode())}",
                        titulo = titulo,
                        autor = "",
                        idioma = lang.uppercase(),
                        urlEpub = urlEpub(titulo, idioma)
                    )
                )
            }
        }
        val proximo = raiz.optJSONObject("continue")?.optInt("sroffset", -1)?.takeIf { it > 0 }
        return ResultadoBusca(livros, proximo)
    }
}
