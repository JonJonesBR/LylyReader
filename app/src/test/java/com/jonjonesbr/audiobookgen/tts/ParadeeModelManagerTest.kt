package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class ParadeeModelManagerTest {

    @Test
    fun `url de cada voz aponta para o arquivo no repositorio do modelo`() {
        val raizPt = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/paradee/ptbr"
        val urlV13 = ParadeeVozes.URL_MODELO_V13
        assertEquals(
            listOf(
                "$raizPt/paradee_dora_int8.onnx",
                "$raizPt/paradee_alex_int8.onnx",
                urlV13,
                urlV13,
                urlV13,
                urlV13,
                urlV13,
            ),
            ParadeeVozes.todas.map { ParadeeModelManager.url(it) },
        )
    }
}
