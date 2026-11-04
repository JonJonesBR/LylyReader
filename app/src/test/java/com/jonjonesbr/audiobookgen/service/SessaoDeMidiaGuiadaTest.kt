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
 * Sessão de mídia da leitura guiada (rb-41): sem ACTION_SEEK_TO. A duração na metadata é a do trecho que está
 * tocando (ou -1 sem trecho), e a posição e a velocidade do PlaybackState vêm do trecho, para a barra do sistema
 * andar sozinha. Robolectric porque os builders são classes do Android.
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
        assertEquals(1f, tocando.playbackSpeed, 0f)

        val pausado = estadoDaSessaoDaGuiada(tocando = false)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, pausado.state)
        assertEquals(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, pausado.position)
        assertEquals(0f, pausado.playbackSpeed, 0f)
    }

    @Test
    fun `estado com trecho tocando leva posicao velocidade e instante da leitura`() {
        val trecho = TrechoNaSessao(
            duracaoMs = 7000, posicaoMs = 1200, velocidade = 1.25f, evento = 1, instanteMs = 42_000L
        )

        val estado = estadoDaSessaoDaGuiada(tocando = true, trecho = trecho, agoraMs = trecho.instanteMs)

        assertEquals(PlaybackStateCompat.STATE_PLAYING, estado.state)
        assertEquals(1200L, estado.position)
        assertEquals(1.25f, estado.playbackSpeed, 0f)
        assertEquals(42_000L, estado.lastPositionUpdateTime)
    }

    @Test
    fun `estado pausado com trecho fica com velocidade zero`() {
        val trecho = TrechoNaSessao(
            duracaoMs = 7000, posicaoMs = 3000, velocidade = 1.25f, evento = 2, instanteMs = 5L
        )

        val estado = estadoDaSessaoDaGuiada(tocando = false, trecho = trecho, agoraMs = 5L)

        assertEquals(PlaybackStateCompat.STATE_PAUSED, estado.state)
        assertEquals(3000L, estado.position)
        assertEquals(0f, estado.playbackSpeed, 0f)
    }

    @Test
    fun `tocando com trecho parado entre oracoes continua PLAYING com velocidade zero`() {
        val trecho = TrechoNaSessao(
            duracaoMs = 4000, posicaoMs = 4000, velocidade = 0f, evento = 3, instanteMs = 9L
        )

        val estado = estadoDaSessaoDaGuiada(tocando = true, trecho = trecho, agoraMs = 9L)

        assertEquals(PlaybackStateCompat.STATE_PLAYING, estado.state)
        assertEquals(4000L, estado.position)
        assertEquals(0f, estado.playbackSpeed, 0f)
    }

    @Test
    fun `metadados sem trecho declaram duracao desconhecida e mantem titulo e nome do app`() {
        val metadata = metadataDaSessaoGuiada("Dom Casmurro", "LylyReader")

        assertEquals(-1L, metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        assertTrue(metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) <= 0L)
        assertEquals("Dom Casmurro", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals("LylyReader", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    }

    @Test
    fun `metadados com trecho declaram a duracao do trecho`() {
        val metadata = metadataDaSessaoGuiada("Dom Casmurro", "LylyReader", duracaoMs = 7000L)

        assertEquals(7000L, metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        assertEquals("Dom Casmurro", metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals("LylyReader", metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
    }
}
