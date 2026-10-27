package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Respostas vazias, truncadas ou sem o formato esperado. Sem rede, o LivrosClient nem chega a
 * interpretar nada (a exceção de IO sobe para a tela). Aqui se testa só o que chega ao parse.
 */
class CatalogoRespostaInvalidaTest {

    // ── Gutenberg (OPDS/XML) ─────────────────────────────────────────────────

    @Test
    fun gutenbergRespostaVaziaFalhaSemDevolverLivrosFalsos() {
        assertThrows(Exception::class.java) { CatalogoGutenberg.interpretar("") }
    }

    @Test
    fun gutenbergFeedTruncadoFalha() {
        val truncado = """<?xml version="1.0"?><feed><entry><title>Dom Casmurro"""
        assertThrows(Exception::class.java) { CatalogoGutenberg.interpretar(truncado) }
    }

    @Test
    fun gutenbergFeedSemEntradasDevolveVazioESemProximaPagina() {
        val resultado = CatalogoGutenberg.interpretar("""<?xml version="1.0"?><feed></feed>""")

        assertTrue(resultado.livros.isEmpty())
        assertNull(resultado.proximoInicio)
    }

    // ── Wikisource (JSON da API do MediaWiki) ────────────────────────────────

    @Test
    fun wikisourceRespostaVaziaFalha() {
        assertThrows(Exception::class.java) { CatalogoWikisource.interpretar("", IdiomaBusca.PT) }
    }

    @Test
    fun wikisourceJsonTruncadoFalha() {
        val truncado = """{"query":{"search":[{"title":"Dom Casm"""
        assertThrows(Exception::class.java) { CatalogoWikisource.interpretar(truncado, IdiomaBusca.PT) }
    }

    @Test
    fun wikisourceObjetoVazioDevolveVazio() {
        val resultado = CatalogoWikisource.interpretar("{}", IdiomaBusca.PT)

        assertTrue(resultado.livros.isEmpty())
        assertNull(resultado.proximoInicio)
    }

    @Test
    fun wikisourceIgnoraTitulosEmBrancoSemDerrubarAsOutras() {
        val json = """{"query":{"search":[{"title":"   "},{"title":"Dom Casmurro"}]}}"""

        val resultado = CatalogoWikisource.interpretar(json, IdiomaBusca.PT)

        assertEquals(listOf("Dom Casmurro"), resultado.livros.map { it.titulo })
    }

    // ── Internet Archive (JSON do advancedsearch e do metadata) ──────────────

    @Test
    fun archiveRespostaVaziaFalha() {
        assertThrows(Exception::class.java) { CatalogoArchive.interpretarBusca("", 1) }
    }

    @Test
    fun archiveBuscaSemResultadosNaoTemProximaPagina() {
        val resultado = CatalogoArchive.interpretarBusca("""{"response":{"numFound":0,"docs":[]}}""", 1)

        assertTrue(resultado.livros.isEmpty())
        assertNull(resultado.proximoInicio)
    }

    @Test
    fun archiveBuscaTruncadaFalha() {
        val truncado = """{"response":{"numFound":3,"docs":[{"identifier":"abc"""
        assertThrows(Exception::class.java) { CatalogoArchive.interpretarBusca(truncado, 1) }
    }

    @Test
    fun archiveMetadataVazioOuSemArquivosNaoEscolheNada() {
        assertNull(CatalogoArchive.escolherArquivo("{}", "abc"))
        assertNull(CatalogoArchive.escolherArquivo("""{"files":[]}""", "abc"))
    }

    @Test
    fun archiveMetadataVazioFalhaEmVezDeInventarUmArquivo() {
        assertThrows(Exception::class.java) { CatalogoArchive.escolherArquivo("", "abc") }
    }
}
