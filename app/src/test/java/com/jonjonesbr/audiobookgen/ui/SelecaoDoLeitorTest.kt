package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun barraComAudioTemQuatroAcoesEmOrdem() {
        assertEquals(
            listOf(
                AcaoDaBarraDeSelecao.GERAR_AUDIO,
                AcaoDaBarraDeSelecao.DESTACAR,
                AcaoDaBarraDeSelecao.PRONUNCIA,
                AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS,
            ),
            acoesDaBarraDeSelecao(comAudio = true),
        )
    }

    @Test
    fun barraSoLerTemDestacarEDefinir() {
        assertEquals(
            listOf(AcaoDaBarraDeSelecao.DESTACAR, AcaoDaBarraDeSelecao.DEFINIR),
            acoesDaBarraDeSelecao(comAudio = false),
        )
    }

    @Test
    fun repinturaSeguraComToqueOuMenuAberto() {
        assertTrue(segurarRepinturaDeSincronia(toqueEmAndamento = true, selecoesAbertas = 0, textoComSelecao = false))
        assertTrue(segurarRepinturaDeSincronia(toqueEmAndamento = false, selecoesAbertas = 1, textoComSelecao = false))
        assertFalse(segurarRepinturaDeSincronia(toqueEmAndamento = false, selecoesAbertas = 0, textoComSelecao = false))
    }

    @Test
    fun repinturaSeguraComTextoSelecionadoComFoco() {
        // Entre o UP do toque longo e a abertura do menu: sem dedo e sem menu, ainda segura.
        assertTrue(segurarRepinturaDeSincronia(toqueEmAndamento = false, selecoesAbertas = 0, textoComSelecao = true))
    }

    @Test
    fun duracaoSoValeNaJanelaDoToque() {
        assertEquals(800L, duracaoDoUltimoToque(duracaoMs = 800L, desdeOUpMs = 10L, janelaMs = 300L))
        // Clique que chega depois da janela (Enter, D-pad) não herda a duração de um toque antigo.
        assertEquals(0L, duracaoDoUltimoToque(duracaoMs = 800L, desdeOUpMs = 1_000L, janelaMs = 300L))
    }

    @Test
    fun toqueCurtoValeComoClique() {
        assertTrue(toqueValeComoClique(duracaoMs = 120L, limiteDoToqueLongoMs = 500L, fechavaSelecao = false))
        // Toque de duração zero (TalkBack, duplo toque): vale.
        assertTrue(toqueValeComoClique(duracaoMs = 0L, limiteDoToqueLongoMs = 500L, fechavaSelecao = false))
    }

    @Test
    fun toqueNoLimiteOuLongoNaoValeComoClique() {
        assertFalse(toqueValeComoClique(duracaoMs = 500L, limiteDoToqueLongoMs = 500L, fechavaSelecao = false))
        assertFalse(toqueValeComoClique(duracaoMs = 900L, limiteDoToqueLongoMs = 500L, fechavaSelecao = false))
    }

    @Test
    fun toqueQueFechaSelecaoNaoValeComoClique() {
        assertFalse(toqueValeComoClique(duracaoMs = 50L, limiteDoToqueLongoMs = 500L, fechavaSelecao = true))
    }

    @Test
    fun repinturasSemSegurarDevolvemNaHora() {
        val repinturas = RepinturasPendentes()
        assertEquals(listOf(1, 2), repinturas.pedir(listOf(1, 2), segurar = false))
        assertEquals(emptyList<Int>(), repinturas.liberar())
    }

    @Test
    fun repinturasSeguradasAcumulamSemRepetir() {
        val repinturas = RepinturasPendentes()
        assertEquals(emptyList<Int>(), repinturas.pedir(listOf(3, 4), segurar = true))
        assertEquals(emptyList<Int>(), repinturas.pedir(listOf(4, 5), segurar = true))
        assertEquals(listOf(3, 4, 5), repinturas.liberar())
        assertEquals(emptyList<Int>(), repinturas.liberar())
    }

    @Test
    fun pedirSemSegurarDespejaPendentesJuntoComAsNovas() {
        val repinturas = RepinturasPendentes()
        repinturas.pedir(listOf(7), segurar = true)
        assertEquals(listOf(7, 8), repinturas.pedir(listOf(8), segurar = false))
    }

    @Test
    fun descartarEsqueceAsPendentes() {
        val repinturas = RepinturasPendentes()
        repinturas.pedir(listOf(1, 2), segurar = true)
        repinturas.descartar()
        assertEquals(emptyList<Int>(), repinturas.liberar())
    }
}
