package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DivisaoDePartesTest {

    private val min = 60_000_000L

    @Test
    fun `audio que cabe numa parte nao tem limite`() {
        assertNull(proximoLimiteUs(0, 100 * min, 710 * min, ModoDivisao.MAXIMO))
    }

    @Test
    fun `modo maximo corta no tamanho maximo`() {
        assertEquals(710 * min, proximoLimiteUs(0, 1000 * min, 710 * min, ModoDivisao.MAXIMO))
    }

    @Test
    fun `modo partes iguais divide o total pelo numero de partes`() {
        // 1000 min com maximo de 710 -> 2 partes de 500
        assertEquals(500 * min, proximoLimiteUs(0, 1000 * min, 710 * min, ModoDivisao.PARTES_IGUAIS))
    }

    @Test
    fun `ultima parte que cabe no maximo nao tem limite`() {
        assertNull(proximoLimiteUs(500 * min, 1000 * min, 710 * min, ModoDivisao.PARTES_IGUAIS))
    }

    @Test
    fun `corte escolhe o silencio mais proximo do limite dentro da janela`() {
        val silencios = listOf(listOf(Silencio(100 * min, 101 * min), Silencio(690 * min, 691 * min), Silencio(705 * min, 706 * min)))
        val corte = escolherCorteUs(silencios, 710 * min, 0, 15 * min)
        assertEquals(705 * min + min / 2, corte)
    }

    @Test
    fun `silencio fora da janela e ignorado e corta no limite`() {
        val silencios = listOf(listOf(Silencio(100 * min, 101 * min)))
        assertEquals(710 * min, escolherCorteUs(silencios, 710 * min, 0, 15 * min))
    }

    @Test
    fun `limiar mais rigido tem prioridade sobre o frouxo`() {
        val rigido = listOf(Silencio(600 * min, 601 * min))
        val frouxo = listOf(Silencio(709 * min, 710 * min - 1))
        val corte = escolherCorteUs(listOf(rigido, frouxo), 710 * min, 0, 150 * min)
        assertEquals(rigido[0].meioUs, corte)
    }

    @Test
    fun `fim de capitulo na janela vence o silencio`() {
        val silencios = listOf(listOf(Silencio(705 * min, 706 * min)))
        val corte = escolherCorteUs(silencios, 710 * min, 0, 15 * min, fronteirasUs = listOf(100 * min, 700 * min))
        assertEquals(700 * min, corte)
    }

    @Test
    fun `fim de capitulo fora da janela e ignorado`() {
        val corte = escolherCorteUs(emptyList(), 710 * min, 0, 15 * min, fronteirasUs = listOf(100 * min))
        assertEquals(710 * min, corte)
    }

    @Test
    fun `analisador acha pausa de um segundo no meio de um tom`() {
        val taxa = 8000
        val tom = ShortArray(taxa) { if (it % 2 == 0) 8000 else -8000 }
        val mudo = ShortArray(taxa)
        val analisador = AnalisadorDeSilencios()
        analisador.alimentar(tom, 1, taxa)
        analisador.alimentar(mudo, 1, taxa)
        analisador.alimentar(tom, 1, taxa)
        analisador.finalizar(taxa)
        val achados = analisador.silenciosPorLimiar()[0]
        assertEquals(1, achados.size)
        assertTrue(achados[0].inicioUs in 980_000L..1_020_000L)
        assertTrue(achados[0].fimUs in 1_980_000L..2_020_000L)
    }

    @Test
    fun `pausa curta demais nao conta como silencio`() {
        val taxa = 8000
        val tom = ShortArray(taxa) { if (it % 2 == 0) 8000 else -8000 }
        val analisador = AnalisadorDeSilencios()
        analisador.alimentar(tom, 1, taxa)
        analisador.alimentar(ShortArray(taxa / 5), 1, taxa)
        analisador.alimentar(tom, 1, taxa)
        analisador.finalizar(taxa)
        assertTrue(analisador.silenciosPorLimiar().all { it.isEmpty() })
    }

    private class SaidaFalsa(val indice: Int, val registro: MutableList<SaidaFalsa>) : SaidaDeParte {
        val tempos = mutableListOf<Long>()
        var duracaoFinal = -1L
        var liberada = false
        override fun gravarAudio(amostra: AmostraCodificada) { tempos.add(amostra.presentationTimeUs) }
        override fun finalizar(duracaoUs: Long) { duracaoFinal = duracaoUs }
        override fun liberar() { liberada = true }
    }

    private fun amostra(pts: Long) = AmostraCodificada(ByteArray(1), pts, 0)

    @Test
    fun `divisor corta no silencio e desloca o tempo da parte seguinte`() {
        val saidas = mutableListOf<SaidaFalsa>()
        val total = 30 * min
        val silencios = listOf(listOf(Silencio(8 * min, 8 * min + 2_000_000)))
        val divisor = DivisorDePartes(
            duracaoTotalUs = total, maxParteUs = 10 * min, modo = ModoDivisao.MAXIMO,
            silencios = { silencios }, janelaUs = 5 * min,
            criarSaida = { SaidaFalsa(it, saidas).also { s -> saidas.add(s) } }
        )
        val corte = 8 * min + 1_000_000
        var t = 0L
        while (t < total) { divisor.receber(amostra(t)); t += 10_000_000 }
        divisor.finalizar()

        assertEquals(4, divisor.partesGravadas)
        assertEquals(4, saidas.size)
        assertTrue(saidas.all { it.liberada })
        assertEquals(corte, saidas[0].duracaoFinal)
        assertTrue(saidas[0].tempos.all { it < corte })
        // a parte 2 começa em zero (com o tempo deslocado) e nada se perde nem se repete
        assertTrue(saidas[1].tempos.first() in 0L..10_000_000L)
        assertEquals(total / 10_000_000, saidas.sumOf { it.tempos.size }.toLong())
        assertTrue(saidas.all { it.duracaoFinal <= 10 * min })
    }

    @Test
    fun `divisor sem limite grava tudo numa parte so`() {
        val saidas = mutableListOf<SaidaFalsa>()
        val divisor = DivisorDePartes(
            duracaoTotalUs = 5 * min, maxParteUs = 10 * min, modo = ModoDivisao.MAXIMO,
            silencios = { emptyList() }, criarSaida = { SaidaFalsa(it, saidas).also { s -> saidas.add(s) } }
        )
        for (i in 0 until 10) divisor.receber(amostra(i * 1_000_000L))
        divisor.finalizar()
        assertEquals(1, divisor.partesGravadas)
        assertEquals(10, saidas[0].tempos.size)
    }
}
