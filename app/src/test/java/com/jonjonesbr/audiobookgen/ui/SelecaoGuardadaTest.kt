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
    fun guardarAoSairSoQuandoLeituraComFocoETrecho() {
        // Os 8 casos: só o parágrafo de leitura, com foco e com trecho marcado, guarda.
        for (leitura in listOf(false, true)) for (foco in listOf(false, true)) for (trecho in listOf(false, true)) {
            val esperado = leitura && foco && trecho
            assertEquals(esperado, guardarAoSairDaTela(leitura, foco, trecho))
        }
    }
}
