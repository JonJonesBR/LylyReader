package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumoBibliotecaRegrasTest {

    private data class Livro(val duracaoMs: Long, val progressoPct: Int)

    private fun calcular(vararg livros: Livro, limite: Int = 95) =
        ResumoBibliotecaRegras.calcular(livros.toList(), { it.duracaoMs }, { it.progressoPct }, limite)

    @Test
    fun `biblioteca vazia zera tudo e nao mostra concluidos`() {
        val r = calcular()
        assertEquals(0, r.total)
        assertEquals(0L, r.duracaoMs)
        assertEquals(0, r.concluidos)
        assertFalse(r.mostrarConcluidos)
    }

    @Test
    fun `soma o total e a duracao de todos os audiobooks`() {
        val r = calcular(Livro(1_000L, 0), Livro(2_500L, 40), Livro(3_000L, 10))
        assertEquals(3, r.total)
        assertEquals(6_500L, r.duracaoMs)
    }

    @Test
    fun `concluido comeca exatamente no limite e nao antes`() {
        val r = calcular(Livro(1L, 94), Livro(1L, 95), Livro(1L, 100))
        assertEquals(2, r.concluidos)
        assertTrue(r.mostrarConcluidos)
    }

    @Test
    fun `em andamento nao conta como concluido`() {
        val r = calcular(Livro(1L, 1), Livro(1L, 50), Livro(1L, 0))
        assertEquals(0, r.concluidos)
        assertFalse(r.mostrarConcluidos)
    }

    @Test
    fun `o limite de concluido vem de quem chama`() {
        val r = calcular(Livro(1L, 80), Livro(1L, 90), limite = 80)
        assertEquals(2, r.concluidos)
    }
}
