package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownMarcacoesTest {

    @Test
    fun `monta titulo capitulo trecho nota e data nessa ordem`() {
        val md = MarkdownMarcacoes.montar(
            "Dom Casmurro",
            listOf(MarcacaoExportavel("Capítulo I", "Trecho aqui", "Minha nota", "27/10/2026, 10:00")),
            "Nota",
        )
        assertEquals(
            "# Dom Casmurro\n\n## Capítulo I\n\n> Trecho aqui\n\n**Nota:** Minha nota\n\n*27/10/2026, 10:00*\n",
            md,
        )
    }

    @Test
    fun `sem capitulo e sem nota a marcacao tem so o trecho e a data`() {
        val md = MarkdownMarcacoes.montar(
            "Livro",
            listOf(MarcacaoExportavel("", "Só o trecho", "", "01/01/2026")),
            "Nota",
        )
        assertEquals("# Livro\n\n> Só o trecho\n\n*01/01/2026*\n", md)
    }

    @Test
    fun `trecho com varias linhas vira citacao em cada linha`() {
        val md = MarkdownMarcacoes.montar(
            "Livro",
            listOf(MarcacaoExportavel("", "primeira\nsegunda", "", "")),
            "Nota",
        )
        assertEquals("# Livro\n\n> primeira\n> segunda\n", md)
    }

    @Test
    fun `titulo vazio nao gera cabecalho e marcacoes seguem na ordem recebida`() {
        val md = MarkdownMarcacoes.montar(
            " ",
            listOf(
                MarcacaoExportavel("", "um", "", ""),
                MarcacaoExportavel("", "dois", "", ""),
            ),
            "Nota",
        )
        assertEquals("> um\n\n> dois\n", md)
    }

    @Test
    fun `sem marcacoes devolve so o titulo`() {
        assertEquals("# Livro\n", MarkdownMarcacoes.montar("Livro", emptyList(), "Nota"))
    }
}
