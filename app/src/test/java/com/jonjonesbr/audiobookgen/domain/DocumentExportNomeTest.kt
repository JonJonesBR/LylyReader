package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentExportNomeTest {

    @Test
    fun aacComNomeMp3ViraM4a() {
        assertEquals("livro.m4a", nomeDoArquivoExportado("livro.mp3", "audio/mp4"))
    }

    @Test
    fun aacSemExtensaoGanhaM4a() {
        assertEquals("livro.m4a", nomeDoArquivoExportado("livro", "audio/mp4"))
    }

    @Test
    fun nomeComPontosTrocaSoAUltimaExtensao() {
        assertEquals("Vol. 1.2 - capitulo.m4a", nomeDoArquivoExportado("Vol. 1.2 - capitulo.mp3", "audio/mp4"))
    }

    @Test
    fun extensaoMp3EmMaiusculasTambemEhTrocada() {
        assertEquals("LIVRO.m4a", nomeDoArquivoExportado("LIVRO.MP3", "audio/mp4"))
    }

    @Test
    fun nomeSoDeExtensaoViraAudiobook() {
        assertEquals("audiobook.m4a", nomeDoArquivoExportado(".mp3", "audio/mp4"))
    }

    @Test
    fun mp3MantemONomeOriginal() {
        assertEquals("livro.mp3", nomeDoArquivoExportado("livro.mp3", "audio/mpeg"))
        assertEquals("livro", nomeDoArquivoExportado("livro", "audio/mpeg"))
    }

    @Test
    fun semExtensaoDeAudioTiraMp3EM4a() {
        assertEquals("livro", semExtensaoDeAudio("livro.mp3"))
        assertEquals("livro", semExtensaoDeAudio("livro.m4a"))
        assertEquals("a.b", semExtensaoDeAudio("a.b.mp3"))
        assertEquals("livro.txt", semExtensaoDeAudio("livro.txt"))
    }
}
