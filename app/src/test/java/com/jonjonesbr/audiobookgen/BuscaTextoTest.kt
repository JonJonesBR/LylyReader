package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuscaTextoTest {

    @Test fun resolveAPosicaoRealDoParagrafo() {
        // Termo aparece nos parágrafos 5, 20 e 47 — resultado "nº 1" (índice 1) é o parágrafo 20.
        val resultados = listOf(5, 20, 47)
        assertEquals(5, posicaoResultadoBusca(resultados, 0))
        assertEquals(20, posicaoResultadoBusca(resultados, 1))
        assertEquals(47, posicaoResultadoBusca(resultados, 2))
    }

    @Test fun naoConfundeIndiceDoResultadoComPosicaoDoParagrafo() {
        // Bug real: comparar/usar indiceAtual como se já fosse a posição do parágrafo.
        // Aqui o índice do resultado (1) é bem diferente da posição real do parágrafo (20).
        val resultados = listOf(5, 20, 47)
        val posicao = posicaoResultadoBusca(resultados, 1)
        assertNotEquals(1, posicao)
        assertEquals(20, posicao)
    }

    @Test fun indiceForaDosLimitesRetornaNull() {
        val resultados = listOf(5, 20, 47)
        assertNull(posicaoResultadoBusca(resultados, -1))
        assertNull(posicaoResultadoBusca(resultados, 3))
    }

    @Test fun listaVaziaRetornaNullParaQualquerIndice() {
        assertNull(posicaoResultadoBusca(emptyList(), 0))
    }
}
