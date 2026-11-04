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
    fun menuComAudioSoGuardaDefinir() {
        assertEquals(listOf(AcaoDaBarraDeSelecao.DEFINIR), acoesDaSelecaoSoNoMenu(comAudio = true))
    }

    @Test
    fun menuSoLerNaoGuardaAcaoNenhuma() {
        assertTrue(acoesDaSelecaoSoNoMenu(comAudio = false).isEmpty())
    }

    @Test
    fun menuENaBarraNaoRepeteAcao() {
        for (comAudio in listOf(true, false)) {
            val repetidas = acoesDaSelecaoSoNoMenu(comAudio).intersect(acoesDaBarraDeSelecao(comAudio).toSet())
            assertTrue("Repetidas com comAudio=$comAudio: $repetidas", repetidas.isEmpty())
        }
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

    /** Toque com slop de 8 px e limite de toque longo de 500 ms, como no Android padrão. */
    private fun toqueCurto(duracaoMs: Long, deslocamentoPx: Float, selecaoAberta: Boolean = false): Boolean =
        ehToqueCurto(duracaoMs, deslocamentoPx, slopPx = 8f, limiteDoToqueLongoMs = 500L, selecaoAberta = selecaoAberta)

    @Test
    fun toqueCurtoRapidoEParadoVale() {
        assertTrue(toqueCurto(duracaoMs = 120L, deslocamentoPx = 0f))
        // Um dedo que treme um pouco, abaixo do slop, ainda é toque.
        assertTrue(toqueCurto(duracaoMs = 120L, deslocamentoPx = 5f))
    }

    @Test
    fun toqueNoLimiteOuLongoNaoVale() {
        assertFalse(toqueCurto(duracaoMs = 500L, deslocamentoPx = 0f))
        assertFalse(toqueCurto(duracaoMs = 900L, deslocamentoPx = 0f))
    }

    @Test
    fun arrasteAlemDoSlopNaoVale() {
        assertFalse(toqueCurto(duracaoMs = 120L, deslocamentoPx = 8f))
        assertFalse(toqueCurto(duracaoMs = 120L, deslocamentoPx = 30f))
    }

    @Test
    fun toqueComSelecaoAbertaNaoVale() {
        assertFalse(toqueCurto(duracaoMs = 50L, deslocamentoPx = 0f, selecaoAberta = true))
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

    @Test
    fun textoIgualComDedoESemSelecaoMantemOTexto() {
        // rb-53: a repintura pendente não pode reescrever o texto sob o dedo.
        assertTrue(manterTextoNoBind(textoJaNaTela = true, sobODedo = true, selecaoAtiva = false))
    }

    @Test
    fun semDedoRefazOTexto() {
        assertFalse(manterTextoNoBind(textoJaNaTela = true, sobODedo = false, selecaoAtiva = false))
    }

    @Test
    fun comSelecaoFeitaSegueOCaminhoDela() {
        assertFalse(manterTextoNoBind(textoJaNaTela = true, sobODedo = true, selecaoAtiva = true))
    }

    @Test
    fun itemReaproveitadoComOutroTextoRefazOTexto() {
        assertFalse(manterTextoNoBind(textoJaNaTela = false, sobODedo = true, selecaoAtiva = false))
    }

    @Test
    fun pedidoDoSetTextDeParagrafoEmFocoNoToqueSemSelecaoNaoVale() {
        // rb-55: é a única combinação que não vale, a repintura da guiada no parágrafo tocado.
        assertFalse(pedidoDeMostrarTrechoVale(modoDeToque = true, comFoco = true, comSelecao = false))
    }

    @Test
    fun pedidoValeNasOutrasSeteCombinacoes() {
        // Fora do toque, sem foco ou com seleção (alças), o pedido segue o caminho de sempre.
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = false, comFoco = true, comSelecao = false))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = false, comFoco = false, comSelecao = false))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = false, comFoco = true, comSelecao = true))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = true, comFoco = false, comSelecao = false))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = true, comFoco = true, comSelecao = true))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = true, comFoco = false, comSelecao = true))
        assertTrue(pedidoDeMostrarTrechoVale(modoDeToque = false, comFoco = false, comSelecao = true))
    }

    // rb-54: a barra da seleção de texto sem o modo do sistema. As 16 combinações, por ramo da decisão.

    @Test
    fun menuAbertoDeixaABarraParaOModo() {
        // Com o menu do sistema aberto, nenhum outro caso mexe na barra: as 8 combinações dão NADA.
        for (viva in listOf(false, true)) for (movimento in listOf(false, true)) for (espera in listOf(false, true)) {
            val passo = passoDaBarraDaSelecao(
                menuAberto = true, selecaoViva = viva, emMovimento = movimento, jaEsperou = espera,
            )
            assertEquals(PassoDaBarraDaSelecao.NADA, passo)
        }
    }

    @Test
    fun semSelecaoVivaEscondeMesmoNoArraste() {
        // Seleção sem dono some na hora, com ou sem movimento e com ou sem espera: 4 combinações, ESCONDER.
        for (movimento in listOf(false, true)) for (espera in listOf(false, true)) {
            val passo = passoDaBarraDaSelecao(
                menuAberto = false, selecaoViva = false, emMovimento = movimento, jaEsperou = espera,
            )
            assertEquals(PassoDaBarraDaSelecao.ESCONDER, passo)
        }
    }

    @Test
    fun emMovimentoNaoMexeNaBarraComSelecaoViva() {
        // Com a lista ou o dedo em movimento, a conferência espera o fim: 2 combinações, NADA.
        for (espera in listOf(false, true)) {
            assertEquals(
                PassoDaBarraDaSelecao.NADA,
                passoDaBarraDaSelecao(menuAberto = false, selecaoViva = true, emMovimento = true, jaEsperou = espera),
            )
        }
    }

    @Test
    fun selecaoVivaSemMenuEsperaDepoisMostra() {
        // Viva, sem menu e parada: a 1ª conferência espera a janela do duplo toque; a 2ª mostra a barra sem menu.
        assertEquals(
            PassoDaBarraDaSelecao.ESPERAR,
            passoDaBarraDaSelecao(menuAberto = false, selecaoViva = true, emMovimento = false, jaEsperou = false),
        )
        assertEquals(
            PassoDaBarraDaSelecao.MOSTRAR_SEM_MENU,
            passoDaBarraDaSelecao(menuAberto = false, selecaoViva = true, emMovimento = false, jaEsperou = true),
        )
    }
}
