package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DicionarioDoLivroTest {

    private val global = PronunciationDictionary(
        listOf(PronunciationEntry("Nyx", "Níks"), PronunciationEntry("Hermione", "Hermáione"))
    )

    @Test
    fun livroSemEntradasDevolveOGlobalSemRefazer() {
        assertSame(global, DicionarioDoLivro.efetivo(global, PronunciationDictionary.EMPTY))
    }

    @Test
    fun livroSobrepoeOGlobalNaMesmaPalavraSemDiferenciarCaixa() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("nyx", "Nix")))

        val efetivo = DicionarioDoLivro.efetivo(global, doLivro)

        assertEquals("Nix", efetivo.find("Nyx")?.spoken)
        assertEquals("Hermáione", efetivo.find("Hermione")?.spoken)
    }

    @Test
    fun livroAcrescentaPalavrasQueOGlobalNaoTem() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))

        val efetivo = DicionarioDoLivro.efetivo(global, doLivro)

        assertEquals("Vóks", efetivo.find("Vox")?.spoken)
        assertEquals("Níks", efetivo.find("Nyx")?.spoken)
    }

    @Test
    fun mesmasEntradasDevolvemOMesmoResultadoSemRefazer() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))

        val primeiro = DicionarioDoLivro.efetivo(global, doLivro)
        val segundo = DicionarioDoLivro.efetivo(global, doLivro)

        assertSame(primeiro, segundo)
    }

    @Test
    fun outroGlobalOuOutroLivroRecalcula() {
        val doLivro = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))
        val primeiro = DicionarioDoLivro.efetivo(global, doLivro)

        val outroLivro = DicionarioDoLivro.efetivo(global, PronunciationDictionary(listOf(PronunciationEntry("Vox", "Voks"))))
        val outroGlobal = DicionarioDoLivro.efetivo(PronunciationDictionary(listOf(PronunciationEntry("Nyx", "Nix"))), doLivro)

        assertEquals("Voks", outroLivro.find("Vox")?.spoken)
        assertEquals("Nix", outroGlobal.find("Nyx")?.spoken)
        assertEquals("Vóks", primeiro.find("Vox")?.spoken)
    }
}
