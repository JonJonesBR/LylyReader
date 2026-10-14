package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EscopoPronunciasTest {

    private val global = PronunciationDictionary(
        listOf(PronunciationEntry("Nyx", "Níks"), PronunciationEntry("Hermione", "Hermáione"))
    )

    @Test
    fun semLivroTodasAsEntradasSaoDeTodosOsLivros() {
        val lista = EscopoPronuncias.listar(global, PronunciationDictionary.EMPTY)

        assertEquals(listOf("Hermione", "Nyx"), lista.map { it.word })
        assertEquals(setOf(EscopoPronuncia.TODOS), lista.map { it.escopo }.toSet())
    }

    @Test
    fun entradaDoLivroSobrepoeAGlobalEFicaComOrigemDoLivro() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("nyx", "Nix")))

        val lista = EscopoPronuncias.listar(global, doLivro)
        val nyx = lista.single { it.word.equals("Nyx", ignoreCase = true) }

        assertEquals("Nix", nyx.spoken)
        assertEquals(EscopoPronuncia.LIVRO, nyx.escopo)
        assertEquals(EscopoPronuncia.TODOS, lista.single { it.word == "Hermione" }.escopo)
    }

    @Test
    fun palavraSoDoLivroAparecePeloMenosUmaVezComOrigemDoLivro() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))

        val lista = EscopoPronuncias.listar(global, doLivro)

        assertEquals(3, lista.size)
        assertEquals(EscopoPronuncia.LIVRO, lista.single { it.word == "Vox" }.escopo)
        assertEquals(listOf("Hermione", "Nyx", "Vox"), lista.map { it.word })
    }
}
