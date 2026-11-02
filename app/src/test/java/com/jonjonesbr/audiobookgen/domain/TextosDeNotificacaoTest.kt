package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextosDeNotificacaoTest {

    @Test
    fun `nome da conversao vem do arquivo de saida sem extensao e com espaco no lugar do underscore`() {
        assertEquals("Um Apologo", TextosDeNotificacao.nomeDaConversao("/x/audiobooks/Um_Apologo.mp3"))
    }

    @Test
    fun `sem caminho de saida o nome da conversao e null`() {
        assertNull(TextosDeNotificacao.nomeDaConversao(null))
        assertNull(TextosDeNotificacao.nomeDaConversao(""))
    }

    @Test
    fun `nome da conversao junta espacos repetidos e underscores`() {
        assertEquals(
            "Um Apologo Vol 2",
            TextosDeNotificacao.nomeDaConversao("/x/audiobooks/Um__Apologo_  Vol_2_.mp3")
        )
    }

    @Test
    fun `nome da conversao tira a extensao m4a`() {
        assertEquals("Um Apologo", TextosDeNotificacao.nomeDaConversao("/x/audiobooks/Um_Apologo.m4a"))
    }

    @Test
    fun `titulo do arquivo de entrada vira legivel sem extensao e sem underscore`() {
        assertEquals("Um Apologo", TextosDeNotificacao.tituloDoArquivo("Um_Apologo.txt"))
        assertEquals("Um Apologo", TextosDeNotificacao.tituloDoArquivo("/storage/livros/Um_Apologo.epub"))
    }

    @Test
    fun `titulo do arquivo mantem o ponto que faz parte do nome`() {
        assertEquals("Dr. Jekyll", TextosDeNotificacao.tituloDoArquivo("Dr. Jekyll.txt"))
    }

    @Test
    fun `percentual do titulo fica entre 0 e 100`() {
        assertEquals(0, TextosDeNotificacao.pctNoTitulo(-5))
        assertEquals(100, TextosDeNotificacao.pctNoTitulo(150))
        assertEquals(18, TextosDeNotificacao.pctNoTitulo(18))
    }

    @Test
    fun `autor do livro tem prioridade sobre a etiqueta do mp3`() {
        assertEquals("Machado", TextosDeNotificacao.autorNoPlayer("Machado", "LylyReader"))
    }

    @Test
    fun `sem autor do livro e com o artista padrao do app nao ha autor`() {
        assertNull(TextosDeNotificacao.autorNoPlayer(null, "LylyReader"))
        assertNull(TextosDeNotificacao.autorNoPlayer(null, " lylyreader "))
    }

    @Test
    fun `autor do livro so com espacos cai na etiqueta`() {
        assertEquals("Poe", TextosDeNotificacao.autorNoPlayer("  ", "Poe"))
    }

    @Test
    fun `sem autor do livro nem etiqueta nao ha autor`() {
        assertNull(TextosDeNotificacao.autorNoPlayer(null, null))
    }
}
