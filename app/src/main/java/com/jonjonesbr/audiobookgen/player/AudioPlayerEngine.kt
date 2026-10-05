package com.jonjonesbr.audiobookgen.player

import java.io.File

interface AudioPlayerEngine {
    fun play(audioFile: File, speed: Float)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Int)
    fun setPlaybackSpeed(speed: Float)
    fun setVolume(volume: Float)
    /** Timbre (tom e agudos) das próximas reproduções e da atual. Só a leitura guiada o usa. */
    fun setTimbre(timbre: TimbreVoz) {}
    fun getCurrentPosition(): Int
    fun getDuration(): Int
    fun isPlaying(): Boolean
    fun setCompletionListener(listener: () -> Unit)
    fun setErrorListener(listener: (String) -> Unit)
    fun release()
}
