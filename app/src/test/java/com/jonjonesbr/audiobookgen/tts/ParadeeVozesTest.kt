package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParadeeVozesTest {

    @Test
    fun `ha duas vozes paradee com os ids e arquivos certos`() {
        assertEquals(
            listOf("kokoro-pf-dora", "kokoro-pm-alex"),
            ParadeeVozes.todas.map { it.voiceId },
        )
        assertEquals("paradee_dora_int8.onnx", ParadeeVozes.spec("kokoro-pf-dora")?.arquivo)
        assertEquals(8461438L, ParadeeVozes.spec("kokoro-pm-alex")?.bytes)
    }

    @Test
    fun `ehParadee e verdadeiro so para os dois ids paradee`() {
        assertTrue(ParadeeVozes.ehParadee("kokoro-pf-dora"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-pm-alex"))
        assertFalse(ParadeeVozes.ehParadee("kokoro-pm-santa"))
    }

    @Test
    fun `vocabulario tem 114 entradas, espaco e til combinante`() {
        assertEquals(114, ParadeeVocab.MAPA.size)
        assertEquals(16L, ParadeeVocab.MAPA[0x20])
        assertEquals(17L, ParadeeVocab.MAPA[0x0303])
    }
}
