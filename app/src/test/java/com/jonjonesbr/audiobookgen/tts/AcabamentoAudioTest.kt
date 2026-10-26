package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AcabamentoAudioTest {

    private val taxa = 1_000 // 1 amostra por ms: fica fácil contar as margens

    private fun silencio(ms: Int) = ShortArray(ms)
    private fun fala(ms: Int, amplitude: Int) =
        ShortArray(ms) { i -> (if (i % 2 == 0) amplitude else -amplitude).toShort() }
    private fun rms(a: ShortArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size)

    @Test fun `apara o silencio das bordas mantendo a folga`() {
        val audio = silencio(500) + fala(200, 5_000) + silencio(700)
        val aparado = AcabamentoAudio.aparar(audio, canais = 1, taxa = taxa)
        // 30 ms antes da fala + 200 ms de fala + 80 ms depois.
        assertEquals(310, aparado.size)
        assertEquals(0, aparado.first().toInt())
        assertEquals(0, aparado.last().toInt())
    }

    @Test fun `audio so de silencio fica como esta`() {
        val audio = silencio(300)
        assertArrayEquals(audio, AcabamentoAudio.aparar(audio, canais = 1, taxa = taxa))
    }

    @Test fun `estereo corta em fronteira de quadro`() {
        val audio = silencio(401) + fala(100, 5_000) + silencio(401)
        val aparado = AcabamentoAudio.aparar(audio, canais = 2, taxa = taxa)
        assertEquals(0, aparado.size % 2)
    }

    @Test fun `volume baixo sobe ate perto do alvo`() {
        val baixo = fala(1_000, 1_000)
        val ajustado = AcabamentoAudio.igualarVolume(baixo)
        assertTrue(rms(ajustado) > 2_900)
    }

    @Test fun `volume alto desce e o pico nunca estoura`() {
        val alto = fala(1_000, 20_000)
        val ajustado = AcabamentoAudio.igualarVolume(alto)
        assertTrue(rms(ajustado) < rms(alto))
        assertTrue(ajustado.all { abs(it.toInt()) <= 31_000 })
    }

    @Test fun `pico alto limita o ganho`() {
        // Volume médio baixo, mas com um pico: o ganho para antes de estourar.
        val audio = fala(1_000, 500) + fala(10, 30_000)
        val ajustado = AcabamentoAudio.igualarVolume(audio)
        assertTrue(ajustado.all { abs(it.toInt()) <= 31_000 })
    }

    @Test fun `silencio total nao muda`() {
        val audio = silencio(100)
        assertArrayEquals(audio, AcabamentoAudio.igualarVolume(audio))
    }
}
