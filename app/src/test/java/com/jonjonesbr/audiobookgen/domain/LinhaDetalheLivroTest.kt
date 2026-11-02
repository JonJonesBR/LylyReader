package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LinhaDetalheLivroTest {

    private val appPt = Locale.forLanguageTag("pt-BR")
    private val appEn = Locale.forLanguageTag("en")

    private fun livro(autor: String, idioma: String? = "Portuguese", ano: String? = null) = LivroEncontrado(
        chave = "gutenberg-1",
        titulo = "Dom Casmurro",
        autor = autor,
        idioma = idioma,
        urlEpub = "https://exemplo.invalido/1.epub",
        ano = ano
    )

    @Test
    fun `mostra autor, idioma no idioma do app, ano e ja baixado`() {
        val linha = linhaDetalheLivro(
            livro("Machado de Assis", ano = "1899"),
            mostrarIdioma = true,
            jaBaixado = true,
            rotuloJaBaixado = "já baixado",
            idiomaDoApp = appPt
        )
        assertEquals("Machado de Assis · Português · 1899 · já baixado", linha)
    }

    @Test
    fun `idioma so aparece com o filtro Todos`() {
        val linha = linhaDetalheLivro(
            livro("Machado de Assis"),
            mostrarIdioma = false,
            jaBaixado = false,
            rotuloJaBaixado = "já baixado",
            idiomaDoApp = appPt
        )
        assertEquals("Machado de Assis", linha)
    }

    @Test
    fun `sem autor nem ano nem idioma a linha fica vazia`() {
        val linha = linhaDetalheLivro(
            livro("", idioma = null),
            mostrarIdioma = true,
            jaBaixado = false,
            rotuloJaBaixado = "já baixado",
            idiomaDoApp = appPt
        )
        assertEquals("", linha)
    }

    @Test
    fun `idioma desconhecido sai como a fonte mandou`() {
        assertEquals("Klingon", nomeDoIdioma("Klingon", appEn))
    }

    @Test
    fun `codigos e nomes da fonte viram o nome no idioma do app`() {
        assertEquals("Português", nomeDoIdioma("PT", appPt))
        assertEquals("Portuguese", nomeDoIdioma("por", appEn))
        assertEquals("English", nomeDoIdioma("EN", appEn))
    }
}
