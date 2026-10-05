package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

class VoiceReferenceAudioTest {
    private val rate = PocketCustomVoices.SAMPLE_RATE

    private fun sinal(segundos: Int, fala: IntRange): ShortArray = ShortArray(segundos * rate) { i ->
        val t = i / rate
        if (t in fala) (8_000 * sin(i * 0.05)).toInt().toShort() else (20 * sin(i * 0.3)).toInt().toShort()
    }

    @Test
    fun `audio curto fica como esta`() {
        val curto = sinal(6, 0..5)
        assertEquals(curto.size, VoiceReferenceAudio.melhorTrecho(curto).size)
    }

    @Test
    fun `audio longo vira 10 segundos e cai na parte falada`() {
        val longo = sinal(40, 18..33)
        val trecho = VoiceReferenceAudio.melhorTrecho(longo)
        assertEquals(PocketCustomVoices.MAX_SECONDS * rate, trecho.size)
        val fracaoComFala = trecho.count { kotlin.math.abs(it.toInt()) > 1_000 } / trecho.size.toDouble()
        assertTrue("o trecho devia ser quase todo fala, mas foi $fracaoComFala", fracaoComFala > 0.6)
    }

    @Test
    fun `wav antigo de 30 segundos e reduzido ao abrir`() {
        val arquivo = File.createTempFile("ref", ".wav")
        try {
            VoiceReferenceAudio.escreverWav(arquivo, sinal(30, 5..28), rate)
            VoiceReferenceAudio.aparar(arquivo)
            assertEquals(44L + PocketCustomVoices.MAX_SECONDS * rate * 2L, arquivo.length())
        } finally {
            arquivo.delete()
        }
    }
}
