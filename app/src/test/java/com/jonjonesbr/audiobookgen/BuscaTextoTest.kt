package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.indiceInicialBusca
import com.jonjonesbr.audiobookgen.util.normalizarConsulta
import com.jonjonesbr.audiobookgen.util.ocorrenciasBusca
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import com.jonjonesbr.audiobookgen.util.prepararParaBusca
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

    private fun achar(texto: String, consulta: String) =
        ocorrenciasBusca(prepararParaBusca(texto), normalizarConsulta(consulta))
            .map { texto.substring(it.first, it.last + 1) }

    @Test fun ignoraAcentosEMaiusculas() {
        assertEquals(listOf("Coração"), achar("O Coração bate.", "coracao"))
        assertEquals(listOf("ação"), achar("Uma ação", "AÇÃO"))
    }

    @Test fun achaFraseComEspacosEAspasDiferentes() {
        assertEquals(listOf("d’água  pura"), achar("Copo d’água  pura.", "d'agua pura"))
        assertEquals(listOf("“Olá”"), achar("Ele disse “Olá” e saiu", "\"ola\""))
    }

    @Test fun achaTodasAsOcorrencias() {
        assertEquals(listOf("casa", "Casa"), achar("casa e Casa", "casa"))
        assertEquals(emptyList<String>(), achar("nada aqui", "casa"))
        assertEquals(emptyList<String>(), achar("qualquer", "   "))
    }

    @Test fun comecaNoParagrafoOndeOLeitorEsta() {
        val resultados = listOf(5, 20, 47)
        assertEquals(0, indiceInicialBusca(resultados, 0))
        assertEquals(1, indiceInicialBusca(resultados, 6))
        assertEquals(2, indiceInicialBusca(resultados, 47))
        assertEquals(0, indiceInicialBusca(resultados, 100))
        assertEquals(-1, indiceInicialBusca(emptyList(), 3))
    }
}
