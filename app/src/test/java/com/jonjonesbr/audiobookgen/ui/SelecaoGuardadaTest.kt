package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rb-58: o trecho guardado quando o parágrafo sai da tela. Regras puras, sem Android. */
class SelecaoGuardadaTest {

    private val texto = "Capítulo um, parágrafo 2. Dona Irene abriu a janela."

    @Test
    fun comecaSemTrecho() {
        val guardada = SelecaoGuardada()
        assertFalse(guardada.ativa)
        assertNull(guardada.trecho())
        assertNull(guardada.trechoNoParagrafo(1, texto))
    }

    @Test
    fun guardarDeixaOTrechoAtivoEComOsValores() {
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertTrue(guardada.ativa)
        assertEquals(2, guardada.posicao)
        assertEquals(0, guardada.inicio)
        assertEquals(8, guardada.fim)
        assertEquals("Capítulo", guardada.texto)
        assertEquals(TrechoSelecionado(0, 8, "Capítulo"), guardada.trecho())
    }

    @Test
    fun trechoNoParagrafoSoDaFaixaDaMesmaPosicaoETexto() {
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertEquals(0 until 8, guardada.trechoNoParagrafo(2, texto))
        // Outra posição: a view reaproveitada para outro parágrafo não recebe o trecho.
        assertNull(guardada.trechoNoParagrafo(3, texto))
    }

    @Test
    fun textoMudadoNaoPintaOTrecho() {
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        // Outro texto no mesmo índice (lista trocada, texto editado): o trecho não vale mais.
        assertNull(guardada.trechoNoParagrafo(2, "Outro parágrafo inteiro."))
    }

    @Test
    fun limitesInvalidosNaoPintam() {
        val guardada = SelecaoGuardada()
        // Fim além do texto: nada é pintado.
        guardada.guardar(0, TrechoSelecionado(0, 500, "x"), texto)
        assertNull(guardada.trechoNoParagrafo(0, texto))
        // Início depois do fim: nada.
        guardada.guardar(0, TrechoSelecionado(9, 4, "x"), texto)
        assertNull(guardada.trechoNoParagrafo(0, texto))
        // Início negativo: nada.
        guardada.guardar(0, TrechoSelecionado(-1, 4, "x"), texto)
        assertNull(guardada.trechoNoParagrafo(0, texto))
    }

    @Test
    fun guardarDeNovoTrocaOTrechoAnterior() {
        val guardada = SelecaoGuardada()
        guardada.guardar(1, TrechoSelecionado(0, 5, "Dona"), texto)
        guardada.guardar(4, TrechoSelecionado(3, 9, "Irene"), texto)
        assertEquals(4, guardada.posicao)
        assertEquals("Irene", guardada.texto)
        assertNull(guardada.trechoNoParagrafo(1, texto))
        assertEquals(3 until 9, guardada.trechoNoParagrafo(4, texto))
    }

    @Test
    fun limparEsqueceTudo() {
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        guardada.limpar()
        assertFalse(guardada.ativa)
        assertNull(guardada.trecho())
        assertNull(guardada.trechoNoParagrafo(2, texto))
    }

    @Test
    fun toqueDentroDoTrechoReabreOTrecho() {
        // rb-60: o toque no caractere dentro do trecho (0 até 8), com a lista parada, devolve o trecho.
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertEquals(0 until 8, toqueReabreTrecho(2, 0, true, guardada, texto))
        assertEquals(0 until 8, toqueReabreTrecho(2, 3, true, guardada, texto))
        assertEquals(0 until 8, toqueReabreTrecho(2, 7, true, guardada, texto))
    }

    @Test
    fun toqueForaDoTrechoNaoReabre() {
        // Sem trecho guardado, nada reabre. Com o trecho 0 até 8, o offset 8 é a fronteira final (vale, ver abaixo) e
        // o 9 já está fora.
        val guardada = SelecaoGuardada()
        assertNull(toqueReabreTrecho(2, 3, true, guardada, texto))
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertNull(toqueReabreTrecho(2, 9, true, guardada, texto))
        assertNull(toqueReabreTrecho(2, 20, true, guardada, texto))
        assertNull(toqueReabreTrecho(2, -1, true, guardada, texto))
    }

    @Test
    fun fronteiraFinalDoTrechoReabre() {
        // Metade direita da última letra (8 é o fim exclusivo) devolve a fronteira 8: conta, para a margem ser
        // simétrica. Sem a lista parada no DOWN, não reabre.
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertEquals(0 until 8, toqueReabreTrecho(2, 8, true, guardada, texto))
        assertNull(toqueReabreTrecho(2, 8, false, guardada, texto))
    }

    @Test
    fun toqueEmOutroParagrafoOuComOTextoMudadoNaoReabre() {
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertNull(toqueReabreTrecho(3, 3, true, guardada, texto))
        assertNull(toqueReabreTrecho(2, 3, true, guardada, "Outro parágrafo inteiro."))
        assertNull(toqueReabreTrecho(-1, 3, true, guardada, texto))
    }

    @Test
    fun listaRolandoNoDownNaoReabre() {
        // Quem para um fling é a lista: o toque com a lista em movimento no DOWN não reabre o trecho.
        val guardada = SelecaoGuardada()
        guardada.guardar(2, TrechoSelecionado(0, 8, "Capítulo"), texto)
        assertNull(toqueReabreTrecho(2, 3, false, guardada, texto))
    }

    @Test
    fun guardarAoSairSoQuandoLeituraComFocoETrecho() {
        // Os 8 casos: só o parágrafo de leitura, com foco e com trecho marcado, guarda.
        for (leitura in listOf(false, true)) for (foco in listOf(false, true)) for (trecho in listOf(false, true)) {
            val esperado = leitura && foco && trecho
            assertEquals(esperado, guardarAoSairDaTela(leitura, foco, trecho))
        }
    }

    // rb-60b: o trecho volta sozinho só com a lista parada, sem dedo, sem outro texto, sem guiada e com o trecho inteiro.
    private fun reabrir(
        rolagemParada: Boolean = true,
        dedoNaTela: Boolean = false,
        outroTextoEmUso: Boolean = false,
        guiadaConduzRolagem: Boolean = false,
        trechoInteiroVisivel: Boolean = true,
    ): Boolean = reabrirSozinho(rolagemParada, dedoNaTela, outroTextoEmUso, guiadaConduzRolagem, trechoInteiroVisivel)

    @Test
    fun reabreSozinhoSoComTodasAsCondicoes() {
        assertTrue(reabrir())
    }

    @Test
    fun rolagemOuDedoOuOutroTextoOuGuiadaOuTrechoCortadoBloqueiam() {
        assertFalse(reabrir(rolagemParada = false))
        assertFalse(reabrir(dedoNaTela = true))
        assertFalse(reabrir(outroTextoEmUso = true))
        assertFalse(reabrir(guiadaConduzRolagem = true))
        assertFalse(reabrir(trechoInteiroVisivel = false))
    }

    private val lista = LinhaNaTela(100, 900)

    @Test
    fun areaUtilSemBarraNemGuiadaSaoOsLimitesDaLista() {
        assertEquals(LinhaNaTela(100, 900), areaUtilParaReabrir(lista, Int.MAX_VALUE, null, null))
    }

    @Test
    fun barraMaisBaixaQueOTopoDaListaVenceOTopo() {
        // Descida grande: a base da barra está em 400, então o topo útil é 400.
        assertEquals(LinhaNaTela(400, 900), areaUtilParaReabrir(lista, Int.MAX_VALUE, 400, null))
    }

    @Test
    fun barraMaisAltaQueOTopoDaListaPerde() {
        assertEquals(LinhaNaTela(100, 900), areaUtilParaReabrir(lista, Int.MAX_VALUE, 50, null))
    }

    @Test
    fun fundoEMenorEntreListaPaiEGuiada() {
        assertEquals(LinhaNaTela(100, 700), areaUtilParaReabrir(lista, 700, null, null))
        assertEquals(LinhaNaTela(100, 650), areaUtilParaReabrir(lista, 700, null, 650))
        assertEquals(LinhaNaTela(100, 800), areaUtilParaReabrir(lista, 800, null, 950))
    }

    @Test
    fun trechoInteiroDentroDaAreaCabe() {
        assertTrue(trechoInteiroNaArea(LinhaNaTela(200, 300), LinhaNaTela(100, 900)))
    }

    @Test
    fun bordasIguaisContamComoDentro() {
        assertTrue(trechoInteiroNaArea(LinhaNaTela(100, 900), LinhaNaTela(100, 900)))
    }

    @Test
    fun umPixoForaDaAreaNaoCabe() {
        val area = LinhaNaTela(100, 900)
        assertFalse(trechoInteiroNaArea(LinhaNaTela(99, 300), area))
        assertFalse(trechoInteiroNaArea(LinhaNaTela(200, 901), area))
    }

    @Test
    fun areaVaziaOuInvertidaNaoCabeNada() {
        assertFalse(trechoInteiroNaArea(LinhaNaTela(200, 200), LinhaNaTela(300, 300)))
        assertFalse(trechoInteiroNaArea(LinhaNaTela(200, 200), LinhaNaTela(900, 100)))
    }
}
