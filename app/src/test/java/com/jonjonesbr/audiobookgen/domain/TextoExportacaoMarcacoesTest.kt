package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextoExportacaoMarcacoesTest {

    @Test
    fun `monta titulo, trecho, nota e capitulo nessa ordem em texto simples`() {
        val texto = TextoExportacaoMarcacoes.montar(
            "Dom Casmurro",
            listOf(MarcacaoExportavel("Capítulo I", "Trecho aqui", "Minha nota")),
            "Nota",
        )
        assertEquals(
            "Dom Casmurro\n\nTrecho aqui\nNota: Minha nota\n(Capítulo I)\n",
            texto,
        )
    }

    @Test
    fun `sem capitulo e nota a marcacao tem so o trecho`() {
        val texto = TextoExportacaoMarcacoes.montar(
            "Livro",
            listOf(MarcacaoExportavel("", "Só o trecho", "")),
            "Nota",
        )
        assertEquals("Livro\n\nSó o trecho\n", texto)
    }

    @Test
    fun `trecho com varias linhas sai como esta, sem simbolos de citacao`() {
        val texto = TextoExportacaoMarcacoes.montar(
            "Livro",
            listOf(MarcacaoExportavel("", "primeira\nsegunda", "")),
            "Nota",
        )
        assertEquals("Livro\n\nprimeira\nsegunda\n", texto)
        assertFalse(texto.contains(">"))
    }

    @Test
    fun `titulo vazio nao gera linha e as marcacoes seguem na ordem recebida`() {
        val texto = TextoExportacaoMarcacoes.montar(
            " ",
            listOf(
                MarcacaoExportavel("", "um", ""),
                MarcacaoExportavel("", "dois", ""),
            ),
            "Nota",
        )
        assertEquals("um\n\ndois\n", texto)
    }

    @Test
    fun `sem marcacoes devolve so o titulo`() {
        assertEquals("Livro\n", TextoExportacaoMarcacoes.montar("Livro", emptyList(), "Nota"))
    }

    @Test
    fun `nao usa Markdown mesmo com titulo e nota que parecem Markdown`() {
        val texto = TextoExportacaoMarcacoes.montar(
            "# Título",
            listOf(MarcacaoExportavel("Cap", "trecho", "**importante**")),
            "Nota",
        )
        assertEquals("# Título\n\ntrecho\nNota: **importante**\n(Cap)\n", texto)
    }
}
