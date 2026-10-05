package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoArchiveTest {

    private val busca = """{"response":{"numFound":45,"start":0,"docs":[
{"identifier":"dom-casmurro_202503","title":"Dom Casmurro","creator":"Machado de Assis","date":"1900-01-01T00:00:00Z","language":"por"},
{"identifier":"outro","title":["Titulo em lista"],"creator":["A","B"],"date":"1899-05-01T00:00:00Z"},
{"identifier":"","title":"sem id"},
{"identifier":"semtitulo"}]}}"""

    @Test
    fun `consulta exige dominio publico, data limite, epub e texto`() {
        val q = CatalogoArchive.montarConsulta("", IdiomaBusca.TODOS)
        assertTrue("mediatype:texts" in q && "format:EPUB" in q)
        assertTrue("licenseurl:\"https://creativecommons.org/publicdomain/mark/1.0/\"" in q)
        assertTrue("licenseurl:\"https://creativecommons.org/publicdomain/zero/1.0/\"" in q)
        assertTrue("date:[* TO 1928-12-31]" in q)
        assertFalse("language:" in q)
        assertFalse("title:" in q)
    }

    @Test
    fun `consulta acrescenta idioma e termos`() {
        val q = CatalogoArchive.montarConsulta("dom casmurro", IdiomaBusca.PT)
        assertTrue("language:por" in q)
        assertTrue("(title:(dom casmurro) OR creator:(dom casmurro))" in q)
    }

    @Test
    fun `termos perdem caracteres de sintaxe da busca`() {
        assertEquals("a b OR c", CatalogoArchive.limparTermos("a:\"b\" (OR) c*"))
        assertEquals("", CatalogoArchive.limparTermos(" :: () "))
    }

    @Test
    fun `url usa a api oficial com pagina e ordena por downloads`() {
        val url = CatalogoArchive.urlBusca("x", IdiomaBusca.ES, 3)
        assertTrue(url.startsWith("https://archive.org/advancedsearch.php?q="))
        assertTrue("page=3" in url && "rows=20" in url && "language%3Aspa" in url && "downloads+desc" in url)
    }

    @Test
    fun `le itens aceitando texto ou lista e ignora os incompletos`() {
        val r = CatalogoArchive.interpretarBusca(busca, 1)
        assertEquals(listOf("archive-dom-casmurro_202503", "archive-outro"), r.livros.map { it.chave })
        val primeiro = r.livros.first()
        assertEquals("Dom Casmurro", primeiro.titulo)
        assertEquals("Machado de Assis", primeiro.autor)
        assertEquals("POR", primeiro.idioma)
        assertEquals("1900", primeiro.ano)
        assertEquals("https://archive.org/services/img/dom-casmurro_202503", primeiro.capaUrl)
        assertEquals("Titulo em lista", r.livros.last().titulo)
        assertEquals("A", r.livros.last().autor)
    }

    @Test
    fun `proxima pagina so enquanto houver resultados`() {
        assertEquals(2, CatalogoArchive.interpretarBusca(busca, 1).proximoInicio)
        assertNull(CatalogoArchive.interpretarBusca(busca, 3).proximoInicio)
    }

    @Test
    fun `resposta sem response nao quebra`() {
        val r = CatalogoArchive.interpretarBusca("{}", 1)
        assertTrue(r.livros.isEmpty())
        assertNull(r.proximoInicio)
    }

    private fun metadata(vararg arquivos: Triple<String, String, Long>) =
        """{"files":[""" + arquivos.joinToString(",") { (n, f, t) -> """{"name":"$n","format":"$f","size":"$t"}""" } + "]}"

    @Test
    fun `prefere o epub e codifica o nome do arquivo`() {
        val a = CatalogoArchive.escolherArquivo(
            metadata(Triple("Dom Casmurro_djvu.txt", "DjVuTXT", 400_000), Triple("Dom Casmurro.epub", "EPUB", 382_362)),
            "dom-casmurro_202503"
        )!!
        assertEquals("epub", a.extensao)
        assertEquals("https://archive.org/download/dom-casmurro_202503/Dom%20Casmurro.epub", a.url)
    }

    @Test
    fun `epub gigante cai para o texto`() {
        val a = CatalogoArchive.escolherArquivo(
            metadata(Triple("x.epub", "EPUB", CatalogoArchive.EPUB_MAX_BYTES + 1), Triple("x_djvu.txt", "DjVuTXT", 1000)),
            "x"
        )!!
        assertEquals("txt", a.extensao)
    }

    @Test
    fun `sem epub nem texto nao ha arquivo`() {
        assertNull(CatalogoArchive.escolherArquivo(metadata(Triple("x.pdf", "Text PDF", 5000)), "x"))
        assertNotNull(CatalogoArchive.escolherArquivo(metadata(Triple("x_djvu.txt", "DjVuTXT", 5000)), "x"))
    }
}
