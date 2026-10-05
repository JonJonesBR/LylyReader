package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogoGutenbergTest {

    private val feed = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
<title>Books: machado</title>
<link rel="next" title="Next Page" href="/ebooks/search.opds/?query=l.pt&amp;sort_order=downloads&amp;start_index=26"/>
<entry>
<title>Authors</title>
<content type="text">One author name matches your search.</content>
<link type="application/atom+xml" rel="subsection" href="/ebooks/authors/search.opds/?query=machado"/>
</entry>
<entry>
<title>Dom Casmurro (Portuguese)</title>
<content type="text">Machado de Assis</content>
<link type="application/atom+xml" rel="subsection" href="/ebooks/55752.opds"/>
<link type="image/jpeg" rel="http://opds-spec.org/image" href="/cache/epub/55752/pg55752.cover.medium.jpg"/>
</entry>
<entry>
<title>The Odyssey</title>
<content type="text">Homer</content>
<link type="application/atom+xml" rel="subsection" href="/ebooks/1727.opds"/>
</entry>
</feed>"""

    @Test
    fun `le livros e ignora entradas que nao sao livros`() {
        val r = CatalogoGutenberg.interpretar(feed)
        assertEquals(listOf("gutenberg-55752", "gutenberg-1727"), r.livros.map { it.chave })
    }

    @Test
    fun `separa idioma do titulo e le o autor`() {
        val livro = CatalogoGutenberg.interpretar(feed).livros.first()
        assertEquals("Dom Casmurro", livro.titulo)
        assertEquals("Portuguese", livro.idioma)
        assertEquals("Machado de Assis", livro.autor)
    }

    @Test
    fun `titulo sem idioma entre parenteses fica inteiro`() {
        val livro = CatalogoGutenberg.interpretar(feed).livros.last()
        assertEquals("The Odyssey", livro.titulo)
        assertNull(livro.idioma)
    }

    @Test
    fun `devolve o link da proxima pagina`() {
        val r = CatalogoGutenberg.interpretar(feed)
        assertEquals(26, r.proximoInicio)
    }

    @Test
    fun `sem link next nao ha proxima pagina`() {
        assertNull(CatalogoGutenberg.interpretar("<feed><title>x</title></feed>").proximoInicio)
    }

    @Test
    fun `url do epub usa o endereco oficial pelo id`() {
        val livro = CatalogoGutenberg.interpretar(feed).livros.first()
        assertEquals("https://www.gutenberg.org/ebooks/55752.epub.noimages", livro.urlEpub)
        assertEquals("Dom Casmurro.epub", livro.nomeArquivo)
        assertEquals("https://www.gutenberg.org/cache/epub/55752/pg55752.cover.small.jpg", livro.capaUrl)
    }

    @Test
    fun `url de busca codifica termos e filtra idioma`() {
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=dom+casmurro+l.pt&sort_order=downloads",
            CatalogoGutenberg.urlBusca(" dom casmurro ", IdiomaBusca.PT)
        )
    }

    @Test
    fun `busca so por idioma e paginacao`() {
        assertEquals(
            "https://www.gutenberg.org/ebooks/search.opds/?query=l.es&sort_order=downloads&start_index=26",
            CatalogoGutenberg.urlBusca("", IdiomaBusca.ES, 26)
        )
    }

    @Test
    fun `feed com doctype e recusado`() {
        val malicioso = """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x SYSTEM "file:///etc/passwd">]><feed>&x;</feed>"""
        val resultado = runCatching { CatalogoGutenberg.interpretar(malicioso) }
        assert(resultado.isFailure)
    }
}
