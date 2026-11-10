package com.jonjonesbr.audiobookgen.player

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FabricaDePlayerTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `chave desligada cria o MediaPlayer`() {
        assertTrue(FabricaDePlayer.criar(context, false) is MediaPlayerEngineImpl)
    }

    @Test
    fun `chave ligada cria o AudioTrack`() {
        assertTrue(FabricaDePlayer.criar(context, true) is AudioTrackEngineImpl)
    }

    @Test
    fun `nomes dos motores`() {
        assertEquals("audiotrack", FabricaDePlayer.nome(true))
        assertEquals("mediaplayer", FabricaDePlayer.nome(false))
    }
}
