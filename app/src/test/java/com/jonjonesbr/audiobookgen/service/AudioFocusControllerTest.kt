package com.jonjonesbr.audiobookgen.service

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusControllerTest {

    @Test
    fun `foco concedido vira tocar`() {
        assertEquals(RespostaDoFoco.TOCAR, respostaDoFoco(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
    }

    @Test
    fun `foco adiado vira esperar, para o onFocusGained tocar depois`() {
        assertEquals(RespostaDoFoco.ESPERAR, respostaDoFoco(AudioManager.AUDIOFOCUS_REQUEST_DELAYED))
    }

    @Test
    fun `foco negado, como numa ligação, não toca`() {
        assertEquals(RespostaDoFoco.NAO_TOCAR, respostaDoFoco(AudioManager.AUDIOFOCUS_REQUEST_FAILED))
    }

    @Test
    fun `resultado desconhecido não toca`() {
        assertEquals(RespostaDoFoco.NAO_TOCAR, respostaDoFoco(-99))
    }
}
