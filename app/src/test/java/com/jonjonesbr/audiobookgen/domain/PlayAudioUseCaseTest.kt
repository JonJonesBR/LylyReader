package com.jonjonesbr.audiobookgen.domain

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayAudioUseCaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `falha em setDataSource na segunda chamada libera o player antigo uma vez e nao vaza o novo`() {
        val primeiro = mockk<MediaPlayer>(relaxed = true)
        val segundo = mockk<MediaPlayer>(relaxed = true) {
            every { setDataSource(any<String>()) } throws IOException("arquivo invalido")
        }
        val fila = ArrayDeque(listOf(primeiro, segundo))
        val useCase = PlayAudioUseCase(context) { fila.removeFirst() }

        useCase.iniciarPreview("/tmp/ok.mp3")
        assertTrue(useCase.tocando.value)

        useCase.iniciarPreview("/tmp/quebrado.mp3")
        assertFalse(useCase.tocando.value)
        verify(exactly = 1) { primeiro.release() }
        verify(exactly = 1) { segundo.release() }
    }

    @Test
    fun `falha em prepare tambem libera o novo MediaPlayer e zera tocando`() {
        val player = mockk<MediaPlayer>(relaxed = true) {
            every { prepare() } throws IllegalStateException("prepare falhou")
        }
        val useCase = PlayAudioUseCase(context) { player }

        useCase.iniciarPreview("/tmp/corrompido.mp3")

        assertFalse(useCase.tocando.value)
        verify(exactly = 1) { player.release() }
    }

    @Test
    fun `iniciarPreview seguido de pararPreview libera o player e atualiza estado`() {
        val player = mockk<MediaPlayer>(relaxed = true) {
            every { isPlaying } returns true
        }
        val useCase = PlayAudioUseCase(context) { player }

        useCase.iniciarPreview("/tmp/amostra.mp3")
        assertTrue(useCase.tocando.value)
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.start() }

        useCase.pararPreview()
        assertFalse(useCase.tocando.value)
        verify(exactly = 1) { player.stop() }
        verify(exactly = 1) { player.release() }
    }
}
