package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDeLivroTest {
    private fun ok(entrada: String) = LinkDeLivro.normalizar(entrada).getOrThrow()
    private fun motivo(entrada: String) = (LinkDeLivro.normalizar(entrada).exceptionOrNull() as LinkException).motivo

    @Test
    fun `endereco simples vira https`() {
        assertEquals("https://exemplo.com/livro.epub", ok("  exemplo.com/livro.epub "))
        assertEquals("http://exemplo.com/a.pdf", ok("http://exemplo.com/a.pdf"))
    }

    @Test
    fun `entradas invalidas sao recusadas`() {
        assertEquals(MotivoLink.VAZIO, motivo("   "))
        assertEquals(MotivoLink.INVALIDO, motivo("file:///sdcard/a.epub"))
        assertEquals(MotivoLink.INVALIDO, motivo("javascript:alert(1)"))
        assertEquals(MotivoLink.INVALIDO, motivo("ftp://exemplo.com/a.epub"))
        assertEquals(MotivoLink.INVALIDO, motivo("localhost"))
        assertEquals(MotivoLink.INVALIDO, motivo("https://"))
    }

    @Test
    fun `link de compartilhamento do drive vira download direto`() {
        val esperado = "https://drive.google.com/uc?export=download&id=1AbC_dEf-123"
        assertEquals(esperado, ok("https://drive.google.com/file/d/1AbC_dEf-123/view?usp=sharing"))
        assertEquals(esperado, ok("https://drive.google.com/open?id=1AbC_dEf-123"))
        assertEquals(esperado, ok(esperado))
    }

    @Test
    fun `dropbox forca download direto`() {
        assertEquals("https://www.dropbox.com/s/abc/livro.epub?dl=1", ok("https://www.dropbox.com/s/abc/livro.epub?dl=0"))
        assertEquals("https://www.dropbox.com/s/abc/livro.epub?dl=1", ok("https://www.dropbox.com/s/abc/livro.epub"))
    }

    @Test
    fun `pagina web e reconhecida pelo tipo`() {
        assertTrue(LinkDeLivro.ehPaginaWeb("text/html; charset=utf-8"))
        assertFalse(LinkDeLivro.ehPaginaWeb("application/epub+zip"))
        assertFalse(LinkDeLivro.ehPaginaWeb(null))
    }

    @Test
    fun `nome vem do cabecalho ou do caminho`() {
        assertEquals("Meu Livro.epub", LinkDeLivro.nomeDoArquivo("https://x.com/a", "attachment; filename=\"Meu Livro.epub\""))
        assertEquals("Café.pdf", LinkDeLivro.nomeDoArquivo("https://x.com/a", "attachment; filename*=UTF-8''Caf%C3%A9.pdf"))
        assertEquals("obra final.txt", LinkDeLivro.nomeDoArquivo("https://x.com/pasta/obra%20final.txt", null))
    }

    @Test
    fun `extensao suportada e garantida pelo tipo do conteudo`() {
        assertEquals("livro.epub", LinkDeLivro.nomeComExtensao("livro.epub", null))
        assertEquals("uc.epub", LinkDeLivro.nomeComExtensao("uc", "application/epub+zip"))
        assertEquals("livro.pdf", LinkDeLivro.nomeComExtensao(null, "application/pdf"))
        assertNull(LinkDeLivro.nomeComExtensao("foto.png", "image/png"))
    }
}
