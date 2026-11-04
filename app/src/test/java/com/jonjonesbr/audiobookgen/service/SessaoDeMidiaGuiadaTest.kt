package com.jonjonesbr.audiobookgen.service

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.jonjonesbr.audiobookgen.ui.LeitorTesteApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sessão de mídia da leitura guiada (rb-41): sem ACTION_SEEK_TO e com duração declarada como desconhecida (-1),
 * para a notificação não desenhar a barra 00:00/00:00. Robolectric porque os builders são classes do Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class SessaoDeMidiaGuiadaTest {

    @Test
    fun `sessao nao anuncia seek para o sistema`() {
        assertEquals(0L, acoesDaSessaoGuiada() and PlaybackStateCompat.ACTION_SEEK_TO)
        assertEquals(0L, estadoDaSessaoDaGuiada(tocando = true).actions and PlaybackStateCompat.ACTION_SEEK_TO)
        assertEquals(0L, estadoDaSessaoDaGuiada(tocando = false).actions and PlaybackStateCompat.ACTION_SEEK_TO)
    }

    @Test
    fun `sessao mantem play pause anterior proximo e parar`() {
        val acoes = acoesDaSessaoGuiada()
        listOf(
            PlaybackStateCompat.ACTION_PLAY,
            PlaybackStateCompat.ACTION_PAUSE,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
            PlaybackStateCompat.ACTION_STOP
        ).forEach { acao -> assertNotEquals(0L, acoes and acao) }
    }

    @Test
    fun `estado reflete tocando e pausado com posicao desconhecida`() {
        val tocando = estadoDaSessaoDaGuiada(tocando = true)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, tocando.state)
        assertEquals(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, tocando.position)

        val pausado = estadoDaSessaoDaGuiada(tocando = false)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, pausado.state)
        assertEquals(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, pausado.position)
    }

    @Test
    fun `metadados declaram duracao desconhecida e mantem titulo e nome do app`() {
        val metadata = metadataDaSessaoGuiada("Dom Casmurro", "LylyReader")

        assertEquals(-1L, metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        assertTrue(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) <= 0L)
        assertEquals("Dom Casmurro", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals("LylyReader", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    }
}
