package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperVitsModelManagerTest {

    @Test
    fun todoPacotePiperTemSha256Fixo() {
        val hex = Regex("[0-9a-f]{64}")
        PiperVitsModelManager.models.forEach { model ->
            assertTrue(model.id, model.sha256?.matches(hex) == true)
        }
        // Hashes diferentes: um copiar/colar errado de um pacote para outro apareceria aqui.
        assertEquals(PiperVitsModelManager.models.size, PiperVitsModelManager.models.map { it.sha256 }.toSet().size)
    }

    @Test
    fun razoBaixaDaUrlPropriaEDemaisVozesDoReleaseK2Fsa() {
        val k2Fsa = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        val razo = PiperVitsModelManager.models.first { it.id == "piper-ptbr-razo" }
        assertEquals(
            "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/piper/" +
                "vits-piper-pt_BR-razo-medium.tar.bz2",
            PiperVitsModelManager.urlDoPacote(razo)
        )
        PiperVitsModelManager.models.filter { it.id != "piper-ptbr-razo" }.forEach { model ->
            assertEquals(model.id, "$k2Fsa${model.archive}.tar.bz2", PiperVitsModelManager.urlDoPacote(model))
        }
    }
}
