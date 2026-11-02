package com.jonjonesbr.audiobookgen.player

import android.media.MediaPlayer
import android.media.PlaybackParams
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer

/**
 * rb-2: shadow de teste do MediaPlayer que registra os [PlaybackParams] recebidos. O [ShadowMediaPlayer] padrão não
 * implementa setPlaybackParams (vira stub que não faz nada), e os testes precisam ver a velocidade e o tom que o motor
 * mandou, e se mandou com o player pausado.
 *
 * Não emula o efeito do aparelho em que PlaybackParams com velocidade > 0 equivale a start(). O motor é quem evita essa
 * chamada com o player pausado (ver setPlaybackSpeed em [MediaPlayerEngineImpl]); os testes conferem a chamada, não o som.
 */
@Implements(MediaPlayer::class)
class ShadowMediaPlayerComParametros : ShadowMediaPlayer() {

    val parametros = mutableListOf<PlaybackParams>()

    @Implementation
    fun setPlaybackParams(params: PlaybackParams) {
        parametros += params
    }
}
