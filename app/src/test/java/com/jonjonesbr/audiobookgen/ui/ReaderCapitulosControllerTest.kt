package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCapitulosControllerTest {

    // Capítulo "Um" tem os parágrafos 0 e 1 (3 e 2 palavras); "Dois" tem o 2 (4 palavras). Total de 100 caracteres.
    private val texto = TextoExtraido(
        capitulos = listOf(Capitulo("Um", 0, 1), Capitulo("Dois", 2, 2)),
        paragrafos = listOf(
            Paragrafo("um dois três", charOffset = 0, indiceCapitulo = 0),
            Paragrafo("quatro cinco", charOffset = 50, indiceCapitulo = 0),
            Paragrafo("seis sete oito nove", charOffset = 80, indiceCapitulo = 1),
        ),
        totalChars = 100,
    )

    // ── progressoNaBarra ──────────────────────────────────────────────────────

    @Test
    fun progressoComTempoRestanteNoCapitulo() {
        // Parágrafo 0 no início do livro; faltam 2 palavras no capítulo (1 minuto).
        assertEquals(ProgressoNaBarra(percentual = 0, minutos = 1), progressoNaBarra(texto, 0, noFim = false))
    }

    @Test
    fun progressoNoUltimoParagrafoDoCapituloNaoTemTempo() {
        assertEquals(ProgressoNaBarra(percentual = 50, minutos = 0), progressoNaBarra(texto, 1, noFim = false))
        assertEquals(ProgressoNaBarra(percentual = 80, minutos = 0), progressoNaBarra(texto, 2, noFim = false))
    }

    @Test
    fun fimDoLivroNaTelaMostraCemPorCentoSemTempo() {
        // Com o fim na tela, o parágrafo de referência não conta: 100% e sem minutos, mesmo que ele seja o primeiro.
        assertEquals(ProgressoNaBarra(percentual = 100, minutos = 0), progressoNaBarra(texto, 0, noFim = true))
    }

    // ── capituloInicialDoPainel ───────────────────────────────────────────────

    @Test
    fun painelAbreNoCapituloDaBarraQuandoExiste() {
        assertEquals(1, capituloInicialDoPainel(naBarra = 1, total = 2, ativo = 0))
    }

    @Test
    fun painelUsaOAtivoQuandoABarraNaoTemCapitulo() {
        assertEquals(1, capituloInicialDoPainel(naBarra = -1, total = 2, ativo = 1))
        // Índice da barra fora da lista deste texto: vale o capítulo ativo do ViewModel.
        assertEquals(0, capituloInicialDoPainel(naBarra = 2, total = 2, ativo = 0))
        assertEquals(1, capituloInicialDoPainel(naBarra = 0, total = 0, ativo = 1))
    }
}
