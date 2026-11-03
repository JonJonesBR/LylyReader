package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Costura [TtsSynthesizer.substitutoParaTestes], usada só pelos testes de interface (rb-46). */
class TtsSynthesizerSubstitutoTest {

    @After
    fun tearDown() {
        TtsSynthesizer.substitutoParaTestes = null
    }

    /** Pede um sintetizador a [TtsSynthesizer.para] com motor [motor]; o construtor não toca no Android. */
    private fun escolher(motor: String): TtsSynthesizer = TtsSynthesizer.para(
        motor,
        mockk<Context>(relaxed = true),
        mockk<PythonEngineUseCase>(relaxed = true),
        null
    ) { error("o motor ONNX não entra neste teste") }

    @Test
    fun semSubstitutoDevolveOSintetizadorReal() {
        assertNull(TtsSynthesizer.substitutoParaTestes)

        assertTrue(escolher("android") is PronunciationSynthesizer)
    }

    @Test
    fun comSubstitutoDevolveOSubstitutoComOMotorPedido() {
        val falso = mockk<TtsSynthesizer>()
        val motoresPedidos = mutableListOf<String>()
        TtsSynthesizer.substitutoParaTestes = { motor ->
            motoresPedidos += motor
            falso
        }

        val escolhido = escolher("edge")

        assertSame(falso, escolhido)
        assertEquals(listOf("edge"), motoresPedidos)
    }

    @Test
    fun aoLimparOSubstitutoVoltaOSintetizadorReal() {
        TtsSynthesizer.substitutoParaTestes = { mockk<TtsSynthesizer>() }
        TtsSynthesizer.substitutoParaTestes = null

        assertTrue(escolher("android") is PronunciationSynthesizer)
    }
}
