package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdiomaDownloadTest {
    @Test
    fun `codigos normalizam regiao e multilingue`() {
        assertEquals(setOf("pt", "en"), IdiomaDownload.codigos(listOf("pt-BR", "en-US", "pt-PT")))
        assertEquals(setOf(IdiomaDownload.MULTILINGUE), IdiomaDownload.codigos(listOf("multilingual")))
        assertEquals(emptySet<String>(), IdiomaDownload.codigos(listOf("", "  ")))
    }

    @Test
    fun `todos mostra tudo inclusive pacote sem idioma`() {
        assertTrue(IdiomaDownload.combina(IdiomaBusca.TODOS, emptySet()))
    }

    @Test
    fun `filtro de idioma mostra so o idioma e os multilingues`() {
        assertTrue(IdiomaDownload.combina(IdiomaBusca.PT, setOf("pt")))
        assertFalse(IdiomaDownload.combina(IdiomaBusca.PT, setOf("en")))
        assertTrue(IdiomaDownload.combina(IdiomaBusca.ES, setOf(IdiomaDownload.MULTILINGUE)))
        assertFalse(IdiomaDownload.combina(IdiomaBusca.EN, emptySet()))
    }

    @Test
    fun `padrao segue o idioma do app e cai em todos`() {
        assertEquals(IdiomaBusca.PT, IdiomaDownload.padraoDoIdioma("pt"))
        assertEquals(IdiomaBusca.EN, IdiomaDownload.padraoDoIdioma("en-US"))
        assertEquals(IdiomaBusca.TODOS, IdiomaDownload.padraoDoIdioma("ja"))
    }
}
