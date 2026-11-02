package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NomeDoAudiobookTest {

    @Test
    fun `usa o titulo da etiqueta quando nao e vazio`() {
        assertEquals(
            "Dom Casmurro",
            NomeDoAudiobook.paraMostrar("  Dom Casmurro ", "Machado de Assis", "Dom_Casmurro.mp3")
        )
    }

    @Test
    fun `sem etiqueta usa o nome do arquivo com espaco no lugar do underscore`() {
        assertEquals("Um Apologo", NomeDoAudiobook.paraMostrar(null, null, "Um_Apologo.mp3"))
    }

    @Test
    fun `etiqueta so com espacos cai no nome do arquivo`() {
        assertEquals("Um Apologo", NomeDoAudiobook.paraMostrar("   ", null, "Um_Apologo.mp3"))
        assertEquals("Um Apologo", NomeDoAudiobook.paraMostrar("", null, "Um_Apologo.m4a"))
    }

    @Test
    fun `underscores e espacos repetidos viram um espaco so`() {
        assertEquals("Um Apologo Vol 2", NomeDoAudiobook.paraMostrar(null, null, "Um__Apologo_  Vol_2_.mp3"))
    }

    @Test
    fun `nome sem extensao de audio continua igual`() {
        assertEquals("livro teste", NomeDoAudiobook.paraMostrar(null, null, "livro_teste"))
    }

    @Test
    fun `nome que vira vazio mostra o nome do arquivo sem extensao`() {
        assertEquals("_", NomeDoAudiobook.paraMostrar(null, null, "_.mp3"))
    }

    @Test
    fun `titulo TIT2 do app (nome do arquivo de entrada) nao vira o nome mostrado`() {
        assertEquals(
            "Dom Casmurro - trecho",
            NomeDoAudiobook.paraMostrar("reader_selection_123", "LylyReader", "Dom Casmurro - trecho.mp3")
        )
    }

    @Test
    fun `MP3 do app com titulo sanitizado mostra o nome do arquivo formatado`() {
        assertEquals("Um Apologo", NomeDoAudiobook.paraMostrar("Um_Apologo", "LylyReader", "Um_Apologo.mp3"))
    }

    @Test
    fun `artista externo mantem o titulo da etiqueta`() {
        assertEquals("Dom Casmurro", NomeDoAudiobook.paraMostrar("Dom Casmurro", "Machado de Assis", "Um_Apologo.mp3"))
    }

    @Test
    fun `titulo da etiqueta de MP3 do app e ignorado`() {
        assertNull(NomeDoAudiobook.tituloDaEtiqueta("reader_selection_123", "LylyReader"))
        assertNull(NomeDoAudiobook.tituloDaEtiqueta("Um_Apologo", " lyly reader "))
    }

    @Test
    fun `titulo da etiqueta vazio ou ausente vira null`() {
        assertNull(NomeDoAudiobook.tituloDaEtiqueta(null, "Machado de Assis"))
        assertNull(NomeDoAudiobook.tituloDaEtiqueta("  ", "Machado de Assis"))
    }

    @Test
    fun `titulo real da etiqueta e devolvido sem espacos`() {
        assertEquals("Dom Casmurro", NomeDoAudiobook.tituloDaEtiqueta(" Dom Casmurro ", "Machado de Assis"))
    }

    @Test
    fun `autor vazio ou ausente vira null`() {
        assertNull(NomeDoAudiobook.autorDaEtiqueta(null))
        assertNull(NomeDoAudiobook.autorDaEtiqueta(""))
        assertNull(NomeDoAudiobook.autorDaEtiqueta("   "))
    }

    @Test
    fun `artista padrao do app nao vira autor`() {
        assertNull(NomeDoAudiobook.autorDaEtiqueta("LylyReader"))
        assertNull(NomeDoAudiobook.autorDaEtiqueta(" lylyreader "))
    }

    @Test
    fun `autor real da etiqueta e devolvido sem espacos`() {
        assertEquals("Machado de Assis", NomeDoAudiobook.autorDaEtiqueta(" Machado de Assis "))
    }

    @Test
    fun `item da fila com titulo da biblioteca mostra o titulo`() {
        assertEquals("Um Apólogo", NomeDoAudiobook.doItemDaFila("Um Apólogo", "Um_Apologo.txt"))
    }

    @Test
    fun `item da fila sem livro na biblioteca mostra o nome do arquivo arrumado`() {
        assertEquals("Um Apologo", NomeDoAudiobook.doItemDaFila(null, "Um_Apologo.txt"))
    }

    @Test
    fun `titulo da biblioteca so com espacos cai no nome do arquivo`() {
        assertEquals("a b c", NomeDoAudiobook.doItemDaFila("  ", "a__b  c.epub"))
    }

    @Test
    fun `item da fila sem extensao mostra o nome inteiro`() {
        assertEquals("semext", NomeDoAudiobook.doItemDaFila(null, "semext"))
    }

    @Test
    fun `item da fila que vira vazio mostra o nome do arquivo`() {
        assertEquals(".txt", NomeDoAudiobook.doItemDaFila(null, ".txt"))
    }
}
