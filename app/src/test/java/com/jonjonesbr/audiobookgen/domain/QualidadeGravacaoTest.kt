package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class QualidadeGravacaoTest {
    private val taxa = 24_000

    private fun senoide(segundos: Int, amplitude: Int, freq: Double = 200.0): ShortArray =
        ShortArray(segundos * taxa) { i -> (amplitude * sin(2 * PI * freq * i / taxa)).toInt().toShort() }

    /** Fala de 0,8 s (senoide de 0,2 de amplitude) seguida de 0,4 s de fundo quase mudo (±164). */
    private fun padraoFala(segundos: Int): ShortArray = ShortArray(segundos * taxa) { i ->
        if (i % 28_800 < 19_200) (6_554 * sin(2 * PI * 200.0 * i / taxa)).toInt().toShort()
        else if (i % 2 == 0) 164 else -164
    }

    /** Mesmo padrão de fala, com fundo ruidoso (±1000): relação sinal/ruído abaixo de 15 dB. */
    private fun padraoRuidoso(segundos: Int): ShortArray = ShortArray(segundos * taxa) { i ->
        if (i % 28_800 < 19_200) (6_554 * sin(2 * PI * 200.0 * i / taxa)).toInt().toShort()
        else if (i % 2 == 0) 1_000 else -1_000
    }

    @Test
    fun `rms de silencio e zero`() {
        assertEquals(0f, rms(ShortArray(100)), 0f)
        assertEquals(0f, rms(ShortArray(0)), 0f)
    }

    @Test
    fun `rms de senoide de amplitude 16384 e cerca de 0,35`() {
        assertEquals(0.3535f, rms(senoide(1, 16_384)), 0.005f)
    }

    @Test
    fun `constante saturada tem clipping total`() {
        assertEquals(1f, proporcaoClipping(ShortArray(100) { 32_767 }), 0f)
    }

    @Test
    fun `silencio total e marcado como muito silencio e curto`() {
        val avaliacao = avaliar(ShortArray(12 * taxa), taxa)
        assertTrue(Aviso.MUITO_SILENCIO in avaliacao.avisos)
        assertTrue(Aviso.CURTA in avaliacao.avisos)
        assertFalse(avaliacao.boa)
    }

    @Test
    fun `3 s de fala e curta`() {
        val avaliacao = avaliar(senoide(3, 6_554), taxa)
        assertTrue(Aviso.CURTA in avaliacao.avisos)
    }

    @Test
    fun `12 s de boa gravacao nao tem avisos`() {
        val avaliacao = avaliar(padraoFala(12), taxa)
        assertTrue("avisos inesperados: ${avaliacao.avisos}", avaliacao.avisos.isEmpty())
        assertTrue(avaliacao.boa)
    }

    @Test
    fun `sinal de rms 0,005 e baixa`() {
        // senoide de amplitude 232 tem RMS ~0,005; sem fala acima de 0,03
        val avaliacao = avaliar(senoide(12, 232), taxa)
        assertTrue(Aviso.BAIXA in avaliacao.avisos)
    }

    @Test
    fun `onda quadrada saturada estoura`() {
        val quadrada = ShortArray(12 * taxa) { i -> if ((i / 100) % 2 == 0) 32_767 else -32_768 }
        val avaliacao = avaliar(quadrada, taxa)
        assertTrue(Aviso.ESTOUROU in avaliacao.avisos)
    }

    @Test
    fun `12 s com 8 s de silencio e muito silencio`() {
        val silencioSeguidoDeFala = ShortArray(12 * taxa) { i ->
            if (i < 8 * taxa) 0 else (6_554 * sin(2 * PI * 200.0 * i / taxa)).toInt().toShort()
        }
        val avaliacao = avaliar(silencioSeguidoDeFala, taxa)
        assertTrue(Aviso.MUITO_SILENCIO in avaliacao.avisos)
    }

    @Test
    fun `fundo ruidoso com fala e ruidosa`() {
        val avaliacao = avaliar(padraoRuidoso(12), taxa)
        assertTrue(Aviso.RUIDOSA in avaliacao.avisos)
    }

    @Test
    fun `gravacao curta nao acusa ruido`() {
        val avaliacao = avaliar(padraoRuidoso(3), taxa)
        assertFalse(Aviso.RUIDOSA in avaliacao.avisos)
    }

    @Test
    fun `faixa insuficiente abaixo de 5 segundos`() {
        assertEquals(Faixa.INSUFICIENTE, classificarProgresso(0f))
        assertEquals(Faixa.INSUFICIENTE, classificarProgresso(4.9f))
    }

    @Test
    fun `faixa ok de 5 a menos de 10 segundos`() {
        assertEquals(Faixa.OK, classificarProgresso(5f))
        assertEquals(Faixa.OK, classificarProgresso(9.9f))
    }

    @Test
    fun `faixa otimo a partir de 10 segundos`() {
        assertEquals(Faixa.OTIMO, classificarProgresso(10f))
        assertEquals(Faixa.OTIMO, classificarProgresso(30f))
    }

    @Test
    fun `avaliacao sem avisos e boa`() {
        assertTrue(Avaliacao(emptyList(), 12f).boa)
        assertFalse(Avaliacao(listOf(Aviso.CURTA), 3f).boa)
    }
}
