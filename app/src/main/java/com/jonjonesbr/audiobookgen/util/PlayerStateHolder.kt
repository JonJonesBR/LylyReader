package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.ui.ReaderViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton que compartilha o estado do player (AudioPlayerService) com o ReaderViewModel
 * sem criar dependência direta entre as duas classes.
 *
 * AudioPlayerService escreve → ReaderViewModel (e a Biblioteca) leem.
 */
object PlayerStateHolder {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> get() = _state

    fun update(new: PlayerState) {
        _state.value = new
    }
}
