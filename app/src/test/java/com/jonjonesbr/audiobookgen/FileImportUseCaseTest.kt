package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.ImportedFileNamePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class FileImportUseCaseTest {

    @Test
    fun nomeVazioUsaOFallback() {
        assertEquals("arquivo", ImportedFileNamePolicy.safeCacheName("", "arquivo", null))
    }

    @Test
    fun nomeSoComEspacosUsaOFallback() {
        assertEquals("fallback", ImportedFileNamePolicy.safeCacheName("   ", "fallback", null))
    }

    @Test
    fun nomeVazioSemFallbackUsaDocumentoComExtensaoDoMime() {
        assertEquals("documento.pdf", ImportedFileNamePolicy.safeCacheName("", "", "application/pdf"))
    }

    @Test
    fun nomeDoisPontosNaoApontaParaPastaPai() {
        assertEquals("documento.pdf", ImportedFileNamePolicy.safeCacheName("..", "fallback", "application/pdf"))
        assertEquals("documento", ImportedFileNamePolicy.safeCacheName("..", "fallback", null))
    }

    @Test
    fun nomeUmPontoNaoApontaParaAPastaAtual() {
        assertEquals("documento", ImportedFileNamePolicy.safeCacheName(".", "fallback", null))
    }

    @Test
    fun removeSegmentosDeCaminhoComBarraEBarraInvertida() {
        assertEquals("segredo", ImportedFileNamePolicy.safeCacheName("../../segredo", "f", null))
        assertEquals("livro.epub", ImportedFileNamePolicy.safeCacheName("pasta\\sub\\livro.epub", "f", null))
    }

    @Test
    fun semExtensaoUsaExtensaoDoMime() {
        assertEquals("livro.epub", ImportedFileNamePolicy.safeCacheName("livro", "f", "application/epub+zip"))
    }

    @Test
    fun semExtensaoEMimeNuloFicaSemExtensao() {
        assertEquals("livro", ImportedFileNamePolicy.safeCacheName("livro", "f", null))
    }

    @Test
    fun mimeDesconhecidoNaoAcrescentaExtensao() {
        assertEquals("livro", ImportedFileNamePolicy.safeCacheName("livro", "f", "image/png"))
    }

    @Test
    fun extensaoSuportadaEhMantidaMesmoEmMaiusculas() {
        assertEquals("LIVRO.PDF", ImportedFileNamePolicy.safeCacheName("LIVRO.PDF", "f", null))
    }

    @Test
    fun extensaoNaoSuportadaGanhaExtensaoDoMime() {
        assertEquals("foto.jpg.pdf", ImportedFileNamePolicy.safeCacheName("foto.jpg", "f", "application/pdf"))
    }

    @Test
    fun espacosEOutrosCaracteresViramUnderline() {
        assertEquals("meu_livro.txt", ImportedFileNamePolicy.safeCacheName("meu livro.txt", "f", null))
    }
}
