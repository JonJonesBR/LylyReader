package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FolgaFrasesCurtasTest {

    private val sr = 44100

    private fun seno(hz: Double, segundos: Double) =
        FloatArray((sr * segundos).toInt()) { (0.5 * sin(2 * PI * hz * it / sr)).toFloat() }

    /** Frequência aproximada pelos cruzamentos de zero (subida), ignorando as bordas. */
    private fun frequencia(audio: FloatArray): Double {
        val de = audio.size / 10
        val ate = audio.size - audio.size / 10
        var cruzamentos = 0
        for (i in de + 1 until ate) if (audio[i - 1] < 0f && audio[i] >= 0f) cruzamentos++
        return cruzamentos / ((ate - de).toDouble() / sr)
    }

    @Test
    fun aplicaSoEmFrasesCurtasComPalavras() {
        assertTrue(FolgaFrasesCurtas.aplica("Como está sua leitura?"))
        assertTrue(FolgaFrasesCurtas.aplica("Vamos para um centro de operações secundário."))
        assertFalse(FolgaFrasesCurtas.aplica("Sim."))
        assertFalse(
            FolgaFrasesCurtas.aplica("Eles erraram o alvo, mas quatro dos meus funcionários foram mortos ontem à noite.")
        )
    }

    @Test
    fun acelerarEncurtaNaProporcaoEMantemOTom() {
        val audio = seno(220.0, 2.0)
        val fator = 1f / FolgaFrasesCurtas.FATOR_VELOCIDADE
        val saida = FolgaFrasesCurtas.acelerar(audio, sr, fator)
        assertEquals((audio.size / fator).toDouble(), saida.size.toDouble(), sr * 0.001)
        assertEquals(220.0, frequencia(saida), 220.0 * 0.03)
        // Sem estouro: a soma das janelas é normalizada.
        assertTrue(saida.all { abs(it) <= 0.55f })
    }

    @Test
    fun fatorUmOuAudioCurtoDemaisVoltaIgual() {
        val audio = seno(220.0, 1.0)
        assertSame(audio, FolgaFrasesCurtas.acelerar(audio, sr, 1f))
        val curto = FloatArray(100)
        assertSame(curto, FolgaFrasesCurtas.acelerar(curto, sr, 1.25f))
    }
}
