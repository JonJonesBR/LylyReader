package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.ReadingStartDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStartDetectorTest {

    private fun cap(titulo: String, inicio: Int) = Capitulo(titulo, inicio, inicio + 1)

    @Test fun listaVaziaRetornaZero() {
        assertEquals(0, ReadingStartDetector.indiceInicio(emptyList()))
    }

    @Test fun prefacioTemPrioridadeSobreCapitulo1() {
        val caps = listOf(cap("Capa", 0), cap("Capítulo 1", 8), cap("Prefácio", 4))
        assertEquals(4, ReadingStartDetector.indiceInicio(caps))
    }

    @Test fun capitulo1QuandoNaoHaPrefacio() {
        val caps = listOf(cap("Sumário", 0), cap("Capítulo 1 - O Começo", 12))
        assertEquals(12, ReadingStartDetector.indiceInicio(caps))
    }

    @Test fun detectaFormasVariadas() {
        assertEquals(3, ReadingStartDetector.indiceInicio(listOf(cap("Chapter 1", 3))))
        assertEquals(5, ReadingStartDetector.indiceInicio(listOf(cap("I", 5))))
        assertEquals(7, ReadingStartDetector.indiceInicio(listOf(cap("01 Introdução", 7))))
    }

    @Test fun semCorrespondenciaRetornaZero() {
        val caps = listOf(cap("Capa", 0), cap("Sumário", 2), cap("Agradecimentos", 4))
        assertEquals(0, ReadingStartDetector.indiceInicio(caps))
    }

    @Test fun indiceNegativoEhClampadoParaZero() {
        assertEquals(0, ReadingStartDetector.indiceInicio(listOf(cap("Prefácio", -3))))
    }

    @Test fun continuarUsaAPosicaoSalvaQuandoEllaExisteNoTexto() {
        val caps = listOf(cap("Prefácio", 4))
        assertEquals(7, ReadingStartDetector.indiceParaContinuar(7, 10, caps))
        assertEquals(0, ReadingStartDetector.indiceParaContinuar(0, 10, caps))
    }

    @Test fun continuarSemPosicaoSalvaVaiAoInicioDetectado() {
        val caps = listOf(cap("Capa", 0), cap("Capítulo 1", 8))
        assertEquals(8, ReadingStartDetector.indiceParaContinuar(-1, 20, caps))
    }

    @Test fun continuarComPosicaoForaDoTextoVaiAoInicioDetectado() {
        val caps = listOf(cap("Capítulo 1", 8))
        assertEquals(8, ReadingStartDetector.indiceParaContinuar(20, 20, caps))
        assertEquals(8, ReadingStartDetector.indiceParaContinuar(25, 20, caps))
    }

    @Test fun continuarSemTextoVaiAoInicioDetectadoOuZero() {
        assertEquals(0, ReadingStartDetector.indiceParaContinuar(3, 0, emptyList()))
        assertEquals(8, ReadingStartDetector.indiceParaContinuar(3, 0, listOf(cap("Capítulo 1", 8))))
    }

    @Test fun filaRetomaAPosicaoSalvaNoMeioDoLivro() {
        val caps = listOf(cap("Capítulo 1", 2))
        assertEquals(5, ReadingStartDetector.indiceParaFilaSequencia(5, 10, caps))
    }

    @Test fun filaRecomecaLivroJaLidoAteOFim() {
        // Posição final gravada ao concluir (último parágrafo): a fila não toca só ele.
        val caps = listOf(cap("Capítulo 1", 2))
        assertEquals(2, ReadingStartDetector.indiceParaFilaSequencia(9, 10, caps))
        assertEquals(0, ReadingStartDetector.indiceParaFilaSequencia(0, 1, emptyList()))
    }

    @Test fun filaSemPosicaoSalvaVaiAoInicioDetectado() {
        val caps = listOf(cap("Capítulo 1", 2))
        assertEquals(2, ReadingStartDetector.indiceParaFilaSequencia(-1, 10, caps))
    }
}
