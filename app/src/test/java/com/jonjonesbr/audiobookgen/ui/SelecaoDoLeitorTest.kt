package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** rb-30: regras puras da seleção de trecho e de parágrafos no Leitor. */
class SelecaoDoLeitorTest {

    private val texto = "Uma frase, duas frases."

    private fun paragrafos(vararg textos: String): List<Paragrafo> =
        textos.mapIndexed { i, t -> Paragrafo(texto = t, charOffset = i * 10, indiceCapitulo = 0) }

    @Test
    fun trechoNormalSaiComOffsetsETexto() {
        val trecho = trechoSelecionado(4, 9, texto)
        assertEquals(TrechoSelecionado(4, 9, "frase"), trecho)
    }

    @Test
    fun trechoInvertidoEhNormalizado() {
        // O Android pode devolver início depois do fim: vale a mesma faixa, na ordem certa.
        assertEquals(TrechoSelecionado(4, 9, "frase"), trechoSelecionado(9, 4, texto))
    }

    @Test
    fun trechoVazioEhNulo() {
        assertNull(trechoSelecionado(3, 3, texto))
    }

    @Test
    fun trechoNegativoEhNulo() {
        // Sem seleção o Android devolve -1.
        assertNull(trechoSelecionado(-1, 5, texto))
        assertNull(trechoSelecionado(-1, -1, texto))
    }

    @Test
    fun trechoForaDoTamanhoEhLimitado() {
        // O fim passa do texto: a faixa vai até o fim, sem estourar.
        val trecho = trechoSelecionado(16, 99, texto)
        assertEquals(TrechoSelecionado(16, texto.length, "frases."), trecho)
        // O início já passa do texto: não há trecho.
        assertNull(trechoSelecionado(99, 120, texto))
    }

    @Test
    fun trechoSoDeEspacosTemTextoEmBranco() {
        // Os offsets são válidos, mas o texto some no trim: quem usa confere.
        val trecho = trechoSelecionado(1, 4, "a   b")
        assertEquals("", trecho?.texto)
    }

    @Test
    fun umParagrafoDaFaixaSaiSozinho() {
        val lista = paragrafos("um", "dois", "três")
        assertEquals("dois", textoDosParagrafos(lista, 1, 1))
    }

    @Test
    fun faixaJuntaParagrafosComLinhaEmBranco() {
        val lista = paragrafos("um", "dois", "três")
        assertEquals("um\n\ndois\n\ntrês", textoDosParagrafos(lista, 0, 2))
    }

    @Test
    fun faixaInvertidaTemOMesmoTexto() {
        val lista = paragrafos("um", "dois", "três")
        assertEquals(textoDosParagrafos(lista, 0, 1), textoDosParagrafos(lista, 1, 0))
    }

    @Test
    fun fimAlemDaListaVaiAteOUltimo() {
        val lista = paragrafos("um", "dois", "três")
        assertEquals("dois\n\ntrês", textoDosParagrafos(lista, 1, 50))
    }

    @Test
    fun inicioForaDaListaEhNulo() {
        val lista = paragrafos("um", "dois")
        assertNull(textoDosParagrafos(lista, 7, 9))
        assertNull(textoDosParagrafos(emptyList(), 0, 0))
    }

    @Test
    fun paragrafosEmBrancoSaoNulo() {
        val lista = paragrafos("  ", "")
        assertNull(textoDosParagrafos(lista, 0, 1))
    }

    @Test
    fun contagemDaSelecao() {
        assertEquals(0, paragrafosNaSelecao(null, null))
        assertEquals(1, paragrafosNaSelecao(2, 2))
        assertEquals(4, paragrafosNaSelecao(2, 5))
        assertEquals(1, paragrafosNaSelecao(2, null))
    }
}
