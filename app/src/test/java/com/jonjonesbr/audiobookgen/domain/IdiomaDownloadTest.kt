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
        assertTrue(IdiomaDownload.combina(FiltroIdioma.TODOS, emptySet()))
    }

    @Test
    fun `filtro de idioma mostra so o idioma e os multilingues`() {
        assertTrue(IdiomaDownload.combina(FiltroIdioma.PT, setOf("pt")))
        assertFalse(IdiomaDownload.combina(FiltroIdioma.PT, setOf("en")))
        assertTrue(IdiomaDownload.combina(FiltroIdioma.ES, setOf(IdiomaDownload.MULTILINGUE)))
        assertFalse(IdiomaDownload.combina(FiltroIdioma.EN, emptySet()))
    }

    @Test
    fun `padrao segue o idioma do app e cai em todos`() {
        assertEquals(FiltroIdioma.PT, IdiomaDownload.padraoDoIdioma("pt"))
        assertEquals(FiltroIdioma.EN, IdiomaDownload.padraoDoIdioma("en-US"))
        assertEquals(FiltroIdioma.TODOS, IdiomaDownload.padraoDoIdioma("ja"))
    }
}
