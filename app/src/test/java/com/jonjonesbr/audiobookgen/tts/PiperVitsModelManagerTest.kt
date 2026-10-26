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
}
