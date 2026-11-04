package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** rb-58: a ação da barra sobre o trecho, com o menu ou guardado. Regra pura: as ações só registram o que recebem. */
class AcoesDaSelecaoDeTextoTest {

    private val chamadas = mutableListOf<String>()

    private val acoes = AcoesDaSelecaoDeTexto(
        gerarAudio = { chamadas += "gerar:$it" },
        selecionarVarios = { chamadas += "varios:$it" },
        destacar = { p, i, f -> chamadas += "destacar:$p:$i:$f" },
        pronuncia = { chamadas += "pronuncia:$it" },
        definir = { chamadas += "definir:$it" },
        excluirOcorrencias = { chamadas += "excluir:$it" },
        aoAbrir = {},
        aoFechar = {},
    )

    private val trecho = TrechoSelecionado(1, 6, "Irene")

    private fun executar(acao: AcaoDaBarraDeSelecao, pos: Int = 4, t: TrechoSelecionado = trecho) {
        acoes.acaoDaBarra(acao, t, pos)?.invoke()
    }

    @Test
    fun cadaAcaoChamaASuaFuncaoComOTrecho() {
        executar(AcaoDaBarraDeSelecao.GERAR_AUDIO)
        executar(AcaoDaBarraDeSelecao.DESTACAR)
        executar(AcaoDaBarraDeSelecao.PRONUNCIA)
        executar(AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS)
        executar(AcaoDaBarraDeSelecao.DEFINIR)
        assertEquals(
            listOf("gerar:Irene", "destacar:4:1:6", "pronuncia:Irene", "varios:4", "definir:Irene"),
            chamadas,
        )
    }

    @Test
    fun trechoEmBrancoNaoAgeNoTexto() {
        // Sem texto, as ações que usam o texto não existem; Destacar e Vários usam só a posição.
        val vazio = TrechoSelecionado(1, 2, "")
        assertNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.GERAR_AUDIO, vazio, 4))
        assertNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.PRONUNCIA, vazio, 4))
        assertNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.DEFINIR, vazio, 4))
        assertNotNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.DESTACAR, vazio, 4))
    }

    @Test
    fun semParagrafoNaoDestacaNemSelecionaVarios() {
        assertNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.DESTACAR, trecho, -1))
        assertNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS, trecho, -1))
        assertNotNull(acoes.acaoDaBarra(AcaoDaBarraDeSelecao.DEFINIR, trecho, -1))
    }
}
