package com.jonjonesbr.audiobookgen.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Leitura do player para a barra da sessão (rb-41): função pura, sem Android. */
class TrechoNaSessaoTest {

    private fun tocando(anterior: TrechoNaSessao?, evento: Long = 1L, instante: Long = 100L) =
        trechoLido(LeituraDoPlayer(7000, 1200, tocando = true, velocidade = 1.25f), anterior, evento, instante)

    @Test
    fun `tocando publica duracao posicao velocidade e o instante da leitura`() {
        val trecho = tocando(anterior = null, evento = 7L, instante = 4_200L)

        assertEquals(TrechoNaSessao(7000L, 1200L, 1.25f, 7L, 4_200L), trecho)
    }

    @Test
    fun `pausado com anterior tocando zera a velocidade e mantem a posicao lida`() {
        val anterior = tocando(anterior = null)
        val pausado = trechoLido(
            LeituraDoPlayer(7000, 3000, tocando = false, velocidade = 1.25f),
            anterior,
            evento = 2L,
            instante = 300L
        )

        assertEquals(7000L, pausado?.duracaoMs)
        assertEquals(3000L, pausado?.posicaoMs)
        assertEquals(0f, pausado?.velocidade ?: -1f, 0f)
        assertEquals(2L, pausado?.evento)
        assertEquals(300L, pausado?.instanteMs)
    }

    @Test
    fun `terminado devolve posicao igual a duracao e velocidade zero`() {
        val fim = trechoLido(
            LeituraDoPlayer(5000, 0, tocando = false, velocidade = 1f, terminou = true),
            anterior = tocando(anterior = null),
            evento = 3L,
            instante = 500L
        )

        assertEquals(5000L, fim?.posicaoMs)
        assertEquals(5000L, fim?.duracaoMs)
        assertEquals(0f, fim?.velocidade ?: -1f, 0f)
        assertEquals(500L, fim?.instanteMs)
    }

    @Test
    fun `duracao zero sem anterior nao publica nada`() {
        val trecho = trechoLido(
            LeituraDoPlayer(0, 0, tocando = false, velocidade = 1f),
            anterior = null,
            evento = 4L,
            instante = 400L
        )

        assertNull(trecho)
    }

    @Test
    fun `duracao zero com anterior tocando devolve a copia parada com evento e instante novos`() {
        val anterior = tocando(anterior = null, evento = 5L, instante = 100L)

        val parado = trechoLido(
            LeituraDoPlayer(0, 0, tocando = true, velocidade = 1f),
            anterior,
            evento = 6L,
            instante = 600L
        )

        assertEquals(anterior?.duracaoMs, parado?.duracaoMs)
        assertEquals(anterior?.posicaoMs, parado?.posicaoMs)
        assertEquals(0f, parado?.velocidade ?: -1f, 0f)
        assertEquals(6L, parado?.evento)
        assertEquals(600L, parado?.instanteMs)
    }

    @Test
    fun `parado com anterior ja parado devolve o anterior intacto mesmo com posicao zero`() {
        val pausado = trechoLido(
            LeituraDoPlayer(8000, 3000, tocando = false, velocidade = 1f),
            anterior = null,
            evento = 7L,
            instante = 700L
        )

        val depois = trechoLido(
            LeituraDoPlayer(8000, 0, tocando = false, velocidade = 1f),
            pausado,
            evento = 8L,
            instante = 800L
        )

        assertSame(pausado, depois)
    }

    @Test
    fun `posicao fora do intervalo fica presa a zero e a duracao`() {
        val negativa = trechoLido(LeituraDoPlayer(7000, -50, tocando = true, velocidade = 1f), null, 9L, 900L)
        val alem = trechoLido(LeituraDoPlayer(7000, 9000, tocando = true, velocidade = 1f), null, 10L, 1_000L)

        assertEquals(0L, negativa?.posicaoMs)
        assertEquals(7000L, alem?.posicaoMs)
    }

    @Test
    fun `velocidade negativa tocando vira zero`() {
        val trecho = trechoLido(LeituraDoPlayer(7000, 100, tocando = true, velocidade = -1f), null, 11L, 1_100L)

        assertEquals(0f, trecho?.velocidade ?: -1f, 0f)
    }
}
