package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DescidaDaSelecaoTest {

    @Test
    fun `linha abaixo da barra nao desce`() {
        val linha = LinhaNaTela(topo = 300, fundo = 360)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 0,
        )
        // topoSem = 300; 240 + 24 - 300 = -36, limitado a 0.
        assertEquals(0, descida)
    }

    @Test
    fun `linha exatamente na margem abaixo da barra nao desce`() {
        val linha = LinhaNaTela(topo = 264, fundo = 324)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 0,
        )
        // topoSem = 264; 240 + 24 - 264 = 0.
        assertEquals(0, descida)
    }

    @Test
    fun `linha sob a barra desce a diferenca mais a margem`() {
        // A linha começa em 150 e a barra vai até 240: falta 90 px, mais 24 de margem.
        val linha = LinhaNaTela(topo = 150, fundo = 210)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 0,
        )
        // 240 + 24 - 150 = 114.
        assertEquals(114, descida)
    }

    @Test
    fun `lista no topo desce a primeira linha para logo abaixo da barra`() {
        // Área começa em 200; primeira linha em 236; barra até 440 (altura 240); margem 24.
        val linha = LinhaNaTela(topo = 236, fundo = 276)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 440, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 0,
        )
        // 440 + 24 - 236 = 228; limite 240 + 24 + 40 = 304, então fica 228.
        assertEquals(228, descida)
    }

    @Test
    fun `mesma linha ja deslocada nao muda o deslocamento`() {
        // A linha original estava em 150; com 114 de deslocamento ela está em 264.
        val linha = LinhaNaTela(topo = 264, fundo = 324)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 114,
        )
        // topoSem = 264 - 114 = 150; 240 + 24 - 150 = 114.
        assertEquals(114, descida)
    }

    @Test
    fun `nova selecao mais abaixo pede zero, mesmo com a lista deslocada`() {
        // Com 114 de deslocamento, a linha em 400 já está abaixo da barra: o necessário é zero.
        val linha = LinhaNaTela(topo = 400, fundo = 460)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 114,
        )
        // topoSem = 400 - 114 = 286; 240 + 24 - 286 = -22, limitado a 0.
        assertEquals(0, descida)
    }

    @Test
    fun `lista deslocada alem do necessario devolve o necessario menor`() {
        // Linha em 300 com 120 de deslocamento: o necessário é menos que o atual, e a função devolve o menor.
        val linha = LinhaNaTela(topo = 300, fundo = 360)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 120,
        )
        // topoSem = 300 - 120 = 180; 240 + 24 - 180 = 84.
        assertEquals(84, descida)
    }

    @Test
    fun `linha acima da area visivel fica limitada ao maximo`() {
        // Limite: altura da barra (240) + margem (24) + altura da linha (60) = 324.
        val linha = LinhaNaTela(topo = -500, fundo = -440)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = 0,
        )
        // 240 + 24 + 500 = 764, limitado a 324.
        assertEquals(324, descida)
    }

    @Test
    fun `deslocamento negativo conta como zero`() {
        val linha = LinhaNaTela(topo = 150, fundo = 210)
        val descida = quantoDescerParaMostrarSelecao(
            linha, fundoDaBarraNaTela = 240, alturaDaBarra = 240, margemPx = 24, deslocamentoAtual = -50,
        )
        // Atual vira 0: 240 + 24 - 150 = 114.
        assertEquals(114, descida)
    }
}
