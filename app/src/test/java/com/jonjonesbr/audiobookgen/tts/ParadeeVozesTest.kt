package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParadeeVozesTest {

    @Test
    fun `ha sete vozes paradee com os ids, pacotes e arquivos certos`() {
        assertEquals(
            listOf(
                "kokoro-pf-dora",
                "kokoro-pm-alex",
                "kokoro-en-af-heart",
                "kokoro-en-bf-isabella",
                "kokoro-en-am-puck",
                "kokoro-en-bm-fable",
                "kokoro-em-alex",
            ),
            ParadeeVozes.todas.map { it.voiceId },
        )
        assertEquals("paradee_dora_int8.onnx", ParadeeVozes.spec("kokoro-pf-dora")?.arquivo)
        assertEquals(8461438L, ParadeeVozes.spec("kokoro-pm-alex")?.bytes)
        assertEquals(ParadeeVozes.PACOTE_ID, ParadeeVozes.spec("kokoro-pf-dora")?.pacoteId)

        assertEquals(ParadeeVozes.PACOTE_ID_V13, ParadeeVozes.spec("kokoro-en-af-heart")?.pacoteId)
        assertEquals(ParadeeVozes.ARQUIVO_V13, ParadeeVozes.spec("kokoro-em-alex")?.arquivo)
        assertEquals(8462503L, ParadeeVozes.spec("kokoro-em-alex")?.bytes)
        assertEquals("en", ParadeeVozes.spec("kokoro-en-bf-isabella")?.vozEspeak)
        assertEquals("es", ParadeeVozes.spec("kokoro-em-alex")?.vozEspeak)
        assertEquals(7, ParadeeVozes.spec("kokoro-em-alex")?.indiceVoz)
        assertEquals(1, ParadeeVozes.arquivosDoPacote(ParadeeVozes.PACOTE_ID_V13).size)
        assertEquals(8462503L, ParadeeVozes.bytesDoPacote(ParadeeVozes.PACOTE_ID_V13))
    }

    @Test
    fun `ehParadee e verdadeiro para ptbr e v13`() {
        assertTrue(ParadeeVozes.ehParadee("kokoro-pf-dora"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-pm-alex"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-en-af-heart"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-en-bf-isabella"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-en-am-puck"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-en-bm-fable"))
        assertTrue(ParadeeVozes.ehParadee("kokoro-em-alex"))
        assertFalse(ParadeeVozes.ehParadee("kokoro-pm-santa"))
    }

    @Test
    fun `vocabulario tem 114 entradas, espaco e til combinante`() {
        assertEquals(114, ParadeeVocab.MAPA.size)
        assertEquals(16L, ParadeeVocab.MAPA[0x20])
        assertEquals(17L, ParadeeVocab.MAPA[0x0303])
    }
}
