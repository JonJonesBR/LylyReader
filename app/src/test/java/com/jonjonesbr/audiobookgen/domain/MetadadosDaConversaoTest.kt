package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadadosDaConversaoTest {

    private val livro = "/x/livros/abc/Um_Ap_logo.epub"

    private fun titulo(saida: String?, tituloDoLivro: String? = "Um Apólogo", autor: String? = "Machado de Assis") =
        MetadadosDaConversao.de(tituloDoLivro, autor, livro, saida).titulo

    @Test
    fun `livro inteiro na biblioteca usa o titulo do livro`() {
        assertEquals("Um Apólogo", titulo("/f/Um_Ap_logo.mp3"))
    }

    @Test
    fun `trecho na biblioteca acrescenta o sufixo ao titulo do livro`() {
        assertEquals("Um Apólogo - trecho", titulo("/f/Um_Ap_logo - trecho.mp3"))
    }

    @Test
    fun `capitulo na biblioteca acrescenta o sufixo ao titulo do livro`() {
        assertEquals("Um Apólogo - Capítulo 1", titulo("/f/Um_Ap_logo - Capítulo 1.mp3"))
    }

    @Test
    fun `sem fronteira depois do nome do livro cai no nome do arquivo`() {
        assertEquals("Um Ap logo2", titulo("/f/Um_Ap_logo2.mp3"))
    }

    @Test
    fun `saida que nao comeca pelo nome do livro cai no nome do arquivo`() {
        assertEquals("Outro Livro", titulo("/f/Outro_Livro.mp3"))
    }

    @Test
    fun `titulo do livro vazio ou em branco cai no nome do arquivo`() {
        assertEquals("Um Ap logo", titulo("/f/Um_Ap_logo.mp3", tituloDoLivro = "   "))
        assertEquals("Um Ap logo", titulo("/f/Um_Ap_logo.mp3", tituloDoLivro = ""))
    }

    @Test
    fun `autor so com espacos vira null`() {
        assertNull(MetadadosDaConversao.de("Um Apólogo", "  ", livro, "/f/Um_Ap_logo.mp3").autor)
    }

    @Test
    fun `autor vem aparado`() {
        val autor = MetadadosDaConversao.de("Um Apólogo", " Machado de Assis ", livro, null).autor
        assertEquals("Machado de Assis", autor)
    }

    @Test
    fun `livro fora da biblioteca usa o nome do arquivo de saida`() {
        assertEquals("Um Ap logo", titulo("/f/Um_Ap_logo.mp3", tituloDoLivro = null, autor = null))
    }

    @Test
    fun `sem caminho de saida o titulo e null`() {
        assertNull(titulo(null))
    }

    @Test
    fun `etiquetas usam a reserva quando nao ha titulo`() {
        val etiquetas = MetadadosDaConversao(titulo = null, tituloDoLivro = null, autor = null).etiquetas("Reserva")
        assertEquals("Reserva", etiquetas.titulo)
        assertNull(etiquetas.autor)
        assertEquals("Reserva", etiquetas.album)
    }

    @Test
    fun `capitulo grava titulo com sufixo e album com o titulo do livro`() {
        val metadados = MetadadosDaConversao.de(
            "Um Apólogo", "Machado de Assis", livro, "/f/Um_Ap_logo - Capítulo 1.mp3"
        )
        val etiquetas = metadados.etiquetas("Reserva")
        assertEquals("Um Apólogo - Capítulo 1", etiquetas.titulo)
        assertEquals("Machado de Assis", etiquetas.autor)
        assertEquals("Um Apólogo", etiquetas.album)
    }

    @Test
    fun `sem livro na biblioteca o album segue o titulo`() {
        val etiquetas = MetadadosDaConversao.de(null, null, livro, "/f/Um_Ap_logo.mp3").etiquetas("Reserva")
        assertEquals("Um Ap logo", etiquetas.titulo)
        assertNull(etiquetas.autor)
        assertEquals("Um Ap logo", etiquetas.album)
    }
}
