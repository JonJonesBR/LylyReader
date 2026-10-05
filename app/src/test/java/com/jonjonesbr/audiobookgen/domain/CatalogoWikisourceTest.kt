package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoWikisourceTest {

    private val resposta = """{"batchcomplete":"","continue":{"sroffset":20,"continue":"-||"},
"query":{"searchinfo":{"totalhits":161},"search":[
{"ns":0,"title":"Dom Casmurro","pageid":5030},
{"ns":0,"title":"Dom Casmurro/I","pageid":5772},
{"ns":0,"title":"A Caridade (Machado de Assis)","pageid":127765}]}}"""

    @Test
    fun `ignora subpaginas e mantem as obras`() {
        val r = CatalogoWikisource.interpretar(resposta, IdiomaBusca.PT)
        assertEquals(listOf("Dom Casmurro", "A Caridade (Machado de Assis)"), r.livros.map { it.titulo })
    }

    @Test
    fun `wikisource nao tem capa`() {
        assertNull(CatalogoWikisource.interpretar(resposta, IdiomaBusca.PT).livros.first().capaUrl)
    }

    @Test
    fun `devolve o deslocamento da proxima pagina`() {
        assertEquals(20, CatalogoWikisource.interpretar(resposta, IdiomaBusca.PT).proximoInicio)
    }

    @Test
    fun `ultima pagina nao tem proximo`() {
        val r = CatalogoWikisource.interpretar("""{"query":{"search":[]}}""", IdiomaBusca.PT)
        assertTrue(r.livros.isEmpty())
        assertNull(r.proximoInicio)
    }

    @Test
    fun `url do epub aponta para o ws-export com idioma e titulo codificado`() {
        assertEquals(
            "https://ws-export.wmcloud.org/?format=epub&lang=pt&page=Dom_Casmurro",
            CatalogoWikisource.urlEpub("Dom Casmurro", IdiomaBusca.PT)
        )
        assertEquals(
            "https://ws-export.wmcloud.org/?format=epub&lang=es&page=La_Celestina_%28Fernando_de_Rojas%29",
            CatalogoWikisource.urlEpub("La Celestina (Fernando de Rojas)", IdiomaBusca.ES)
        )
    }

    @Test
    fun `todos os idiomas cai no portugues`() {
        assertEquals("pt", CatalogoWikisource.idiomaEfetivo(IdiomaBusca.TODOS))
        assertTrue(CatalogoWikisource.urlBusca("x", IdiomaBusca.TODOS).startsWith("https://pt.wikisource.org/"))
    }

    @Test
    fun `url de busca usa o site do idioma e so a pagina principal`() {
        val url = CatalogoWikisource.urlBusca("dom casmurro", IdiomaBusca.EN, 20)
        assertTrue(url.startsWith("https://en.wikisource.org/w/api.php?"))
        assertTrue("srsearch=dom+casmurro" in url && "srnamespace=0" in url && "sroffset=20" in url)
    }

    @Test
    fun `nome do arquivo e o titulo sem caracteres invalidos`() {
        val livro = CatalogoWikisource.interpretar(resposta, IdiomaBusca.PT).livros.last()
        assertEquals("A Caridade (Machado de Assis).epub", livro.nomeArquivo)
        val estranho = LivroEncontrado("k", "AC/DC: \"o\" livro?", "", null, "u")
        assertEquals("AC DC o livro.epub", estranho.nomeArquivo.replace("  ", " "))
    }
}
