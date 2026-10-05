package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStateHolderTest {

    @org.junit.Before fun setUp() {
        PlayerStateHolder.update(PlayerState()) // reseta para estado padrão
    }

    @Test fun estadoInicialTemValoresPadrao() = runBlocking {
        val state = PlayerStateHolder.state.first()
        assertEquals(false, state.isVisible)
        assertEquals(false, state.isPlaying)
        assertEquals(0, state.currentPosition)
        assertEquals(0, state.duration)
        assertEquals("", state.trackName)
        assertEquals(1.0f, state.speed)
        assertEquals("", state.caminho)
    }

    @Test fun updateAlteraEstado() = runBlocking {
        PlayerStateHolder.update(PlayerState(isVisible = true, isPlaying = true, trackName = "teste"))
        val state = PlayerStateHolder.state.first()
        assertEquals(true, state.isVisible)
        assertEquals(true, state.isPlaying)
        assertEquals("teste", state.trackName)
    }

    @Test fun updateSubsequenteSobrescreve() = runBlocking {
        PlayerStateHolder.update(PlayerState(isPlaying = true))
        PlayerStateHolder.update(PlayerState(isPlaying = false))
        val state = PlayerStateHolder.state.first()
        assertEquals(false, state.isPlaying)
    }
}
