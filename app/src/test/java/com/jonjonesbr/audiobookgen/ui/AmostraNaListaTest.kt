package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AmostraNaListaTest {

    private val a = "voz-a"
    private val b = "voz-b"

    @Test
    fun estadoInicialDeixaQualquerVozParada() {
        val lista = AmostraNaLista()
        assertEquals(FaseDaAmostra.PARADA, lista.faseDa(a))
        assertEquals(FaseDaAmostra.PARADA, lista.faseDa(b))
    }

    @Test
    fun tocarVozParadaPoeAGeracaoDela() {
        val lista = AmostraNaLista().aoTocar(a)
        assertEquals(FaseDaAmostra.GERANDO, lista.faseDa(a))
    }

    @Test
    fun tocarDeNovoNaVozGerandoCancelaALinha() {
        val lista = AmostraNaLista().aoTocar(a).aoTocar(a)
        assertEquals(FaseDaAmostra.PARADA, lista.faseDa(a))
        assertEquals(AmostraNaLista(), lista)
    }

    @Test
    fun fimNaturalDaPreviaVoltaAParada() {
        val lista = AmostraNaLista().aoTocar(a)
            .aoReceber(a, FaseDaAmostra.TOCANDO)
            .aoReceber(a, FaseDaAmostra.PARADA)
        assertEquals(FaseDaAmostra.PARADA, lista.faseDa(a))
        assertEquals(AmostraNaLista(), lista)
    }

    @Test
    fun tocarOutraVozSubstituiAAnterior() {
        val lista = AmostraNaLista().aoTocar(a).aoReceber(a, FaseDaAmostra.TOCANDO).aoTocar(b)
        assertEquals(FaseDaAmostra.GERANDO, lista.faseDa(b))
        assertEquals(FaseDaAmostra.PARADA, lista.faseDa(a))
    }

    @Test
    fun avisoAtrasadoDePararDaVozAnteriorNaoMexeNaNova() {
        val lista = AmostraNaLista().aoTocar(a).aoReceber(a, FaseDaAmostra.TOCANDO)
            .aoTocar(b)
            .aoReceber(a, FaseDaAmostra.PARADA)
        assertEquals(FaseDaAmostra.GERANDO, lista.faseDa(b))
        assertEquals(FaseDaAmostra.GERANDO, lista.fase)
    }

    @Test
    fun tocandoAtrasadoDepoisDoStopNaoAcendeNada() {
        val lista = AmostraNaLista().aoTocar(a).aoTocar(a)
            .aoReceber(a, FaseDaAmostra.TOCANDO)
        assertEquals(AmostraNaLista(), lista)
    }

    @Test
    fun avisoDeVozDesconhecidaComTudoParadoNaoTemEfeito() {
        val parada = AmostraNaLista()
        assertEquals(parada, parada.aoReceber("desconhecida", FaseDaAmostra.TOCANDO))
        assertEquals(parada, parada.aoReceber("desconhecida", FaseDaAmostra.PARADA))
    }

    @Test
    fun pedidoDiferenteDoAtualEhParadaMesmoComGerandoETocando() {
        assertEquals(FaseDaAmostra.PARADA, faseDoPedido(1, 2, gerando = true, tocando = true))
    }

    @Test
    fun gerandoETocandoDaGerando() {
        assertEquals(FaseDaAmostra.GERANDO, faseDoPedido(3, 3, gerando = true, tocando = true))
    }

    @Test
    fun soTocandoDaTocando() {
        assertEquals(FaseDaAmostra.TOCANDO, faseDoPedido(3, 3, gerando = false, tocando = true))
    }

    @Test
    fun nadaDaParada() {
        assertEquals(FaseDaAmostra.PARADA, faseDoPedido(3, 3, gerando = false, tocando = false))
    }
}
