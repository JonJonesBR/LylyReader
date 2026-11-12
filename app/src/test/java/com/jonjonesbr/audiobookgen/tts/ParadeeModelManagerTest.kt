package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class ParadeeModelManagerTest {

    @Test
    fun `url de cada voz aponta para o arquivo no repositorio do modelo`() {
        val raiz = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/paradee/ptbr"
        assertEquals(
            listOf("$raiz/paradee_dora_int8.onnx", "$raiz/paradee_alex_int8.onnx"),
            ParadeeVozes.todas.map { ParadeeModelManager.url(it) },
        )
    }
}
