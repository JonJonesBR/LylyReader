package com.jonjonesbr.audiobookgen.player

import android.content.Context

/**
 * Escolhe o motor da leitura guiada pela preferência `player_audiotrack`. Com a chave desligada, cria o
 * MediaPlayerEngineImpl direto, como sempre foi (o teste da guiada depende disso).
 */
object FabricaDePlayer {
    fun criar(context: Context, usarAudioTrack: Boolean): AudioPlayerEngine =
        if (usarAudioTrack) AudioTrackEngineImpl(context) else MediaPlayerEngineImpl(context)

    /** Nome do motor, gravado no diagnóstico (`guiada.config`). */
    fun nome(usarAudioTrack: Boolean): String = if (usarAudioTrack) "audiotrack" else "mediaplayer"
}
