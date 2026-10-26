package com.jonjonesbr.audiobookgen.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudiobookCompartilhavelTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("compartilhavel").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aceitaAudioExistente() {
        val mp3 = File(dir, "Livro.MP3").apply { writeText("x") }
        assertEquals(mp3.canonicalFile, audiobookCompartilhavel(mp3.absolutePath))
    }

    @Test
    fun recusaLivrosLogsEPreferencias() {
        listOf("livro.epub", "erro_123.txt", "audiobookgen_prefs.xml", "lylyreader.db").forEach { nome ->
            val arquivo = File(dir, nome).apply { writeText("x") }
            assertNull(nome, audiobookCompartilhavel(arquivo.absolutePath))
        }
    }

    @Test
    fun recusaCaminhoInexistenteVazioOuPasta() {
        assertNull(audiobookCompartilhavel(File(dir, "nao_existe.mp3").absolutePath))
        assertNull(audiobookCompartilhavel(""))
        assertNull(audiobookCompartilhavel(File(dir, "pasta.mp3").apply { mkdirs() }.absolutePath))
    }

    @Test
    fun resolveODoisPontosAntesDeConferir() {
        File(dir, "sub").mkdirs()
        File(dir, "segredo.txt").writeText("x")
        assertNull(audiobookCompartilhavel(File(dir, "sub/../segredo.txt").path))
    }
}
