package com.jonjonesbr.audiobookgen.player

import com.jonjonesbr.audiobookgen.domain.CabecalhoWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReproducaoPcmTest {

    private fun cabecalho(
        formatoTag: Int = 1,
        canais: Int = 1,
        taxa: Int = 44100,
        bits: Int = 16,
        tamanho: Long = 88200L
    ) = CabecalhoWav(formatoTag, canais, taxa, bits, 44L, tamanho)

    @Test
    fun `aceita PCM 16 bits mono 44100`() {
        val pcm = avaliarWavPcm16(cabecalho())
        assertEquals(PcmWav(44100, 1, 44L, 88200), pcm)
    }

    @Test
    fun `recusa formato float`() {
        assertNull(avaliarWavPcm16(cabecalho(formatoTag = 3)))
    }

    @Test
    fun `recusa 8 bits`() {
        assertNull(avaliarWavPcm16(cabecalho(bits = 8)))
    }

    @Test
    fun `recusa mais de dois canais`() {
        assertNull(avaliarWavPcm16(cabecalho(canais = 3)))
    }

    @Test
    fun `recusa dados vazios`() {
        assertNull(avaliarWavPcm16(cabecalho(tamanho = 0L)))
    }

    @Test
    fun `aceita exatamente no teto e recusa acima dele`() {
        assertTrue(avaliarWavPcm16(cabecalho(tamanho = TETO_PCM_BYTES)) != null)
        assertNull(avaliarWavPcm16(cabecalho(tamanho = TETO_PCM_BYTES + 2)))
    }

    @Test
    fun `sem cabecalho nao aceita`() {
        assertNull(avaliarWavPcm16(null))
    }

    @Test
    fun `corta o excedente que nao forma um quadro`() {
        val pcm = avaliarWavPcm16(cabecalho(tamanho = 1001L))
        assertEquals(1000, pcm?.tamanhoDados)
    }

    @Test
    fun `estereo corta por quadro de 4 bytes`() {
        val pcm = avaliarWavPcm16(cabecalho(canais = 2, tamanho = 1003L))
        assertEquals(1000, pcm?.tamanhoDados)
        assertEquals(2, pcm?.canais)
    }

    @Test
    fun `framesParaMs converte quadros em milissegundos`() {
        assertEquals(1000, framesParaMs(44100, 44100))
        assertEquals(2000, framesParaMs(88200, 44100))
        assertEquals(0, framesParaMs(44100, 0))
    }

    @Test
    fun `precisaDeParametros so vale fora de 1,0 e 1,0`() {
        assertFalse(precisaDeParametros(1f, 1f))
        assertTrue(precisaDeParametros(1.2f, 1f))
        assertTrue(precisaDeParametros(1f, 1.06f))
    }
}
