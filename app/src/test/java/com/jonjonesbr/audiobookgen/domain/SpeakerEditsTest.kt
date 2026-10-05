package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SpeakerEditsTest {

    private val livro = listOf(
        "— Venha aqui — disse Marina.",
        "— Já vou — respondeu Pedro.",
        "— Não demore — disse Marina.",
        "— Estou indo — falou Pedro."
    )
    private val marina = OfflineSpeakerAttributor.idOf("Marina")
    private val pedro = OfflineSpeakerAttributor.idOf("Pedro")

    @Test
    fun `analise local acha os dois personagens`() {
        val ids = OfflineSpeakerAttributor.analyze(livro).speakers.map { it.id }
        assertTrue(marina in ids)
        assertTrue(pedro in ids)
    }

    @Test
    fun `fusao faz um personagem virar o outro`() {
        val edits = SpeakerEdits.EMPTY.withMerge(pedro, "Marina")
        val resultado = OfflineSpeakerAttributor.analyze(livro, edits = edits)
        assertEquals(listOf(marina), resultado.speakers.map { it.id })
        assertEquals(4, resultado.speakers.single().utteranceCount)
    }

    @Test
    fun `fusao encadeada aponta para o destino final`() {
        val edits = SpeakerEdits.EMPTY.withMerge(pedro, "Marina").withMerge(marina, "Ana")
        assertEquals("Ana", edits.aliases[pedro])
        assertEquals("Ana", edits.aliases[marina])
    }

    @Test
    fun `fala corrigida a mao troca de falante e vence a ia`() {
        val porIa = mapOf((1 to 1) to "Marina")
        val manual = SpeakerEdits.EMPTY.withLine(1, 1, "Pedro")
        val resultado = OfflineSpeakerAttributor.analyze(livro, aiOverrides = porIa, edits = manual)
        assertEquals(pedro, resultado.paragraphs[1].segments.first().speakerId)
        val outro = OfflineSpeakerAttributor.analyze(livro, edits = SpeakerEdits.EMPTY.withLine(0, 1, "Pedro"))
        assertEquals(pedro, outro.paragraphs[0].segments.first().speakerId)
    }

    @Test
    fun `fala corrigida para narrador some do personagem`() {
        val edits = SpeakerEdits.EMPTY.withLine(0, 1, OfflineSpeakerAttributor.AI_NARRATOR)
        val resultado = OfflineSpeakerAttributor.analyze(livro, edits = edits)
        assertFalse(resultado.paragraphs[0].segments.any { it.speakerId == marina })
    }

    @Test
    fun `lista as falas de um personagem com o numero usado na correcao`() {
        val resultado = OfflineSpeakerAttributor.analyze(livro)
        val falas = SpeechLines.of(resultado, livro, marina)
        assertEquals(listOf(0, 2), falas.map { it.paragraphIndex })
        assertTrue(falas.all { it.ordinal == 1 })
    }

    @Test
    fun `guarda e recupera, e muda o livro invalida`() {
        val pasta = Files.createTempDirectory("edits").toFile()
        val store = SpeakerEditsStore(pasta)
        val edits = SpeakerEdits.EMPTY.withMerge(pedro, "Marina").withLine(2, 1, "Pedro")
        assertTrue(store.save("livro.epub", livro, edits))
        assertEquals(edits, store.load("livro.epub", livro))
        assertTrue(store.load("livro.epub", livro + "novo").isEmpty())
        store.save("livro.epub", livro, SpeakerEdits.EMPTY)
        assertTrue(store.load("livro.epub", livro).isEmpty())
        pasta.deleteRecursively()
    }
}
