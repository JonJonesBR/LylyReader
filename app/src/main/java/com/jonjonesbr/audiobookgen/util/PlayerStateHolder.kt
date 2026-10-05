package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.ui.ReaderViewModel
import com.jonjonesbr.audiobookgen.domain.PlayAudioUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton que compartilha o estado do player entre PlayAudioUseCase e ReaderViewModel
 * sem criar dependência direta entre as duas classes.
 *
 * PlayAudioUseCase escreve → ReaderViewModel lê.
 */
object PlayerStateHolder {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> get() = _state

    fun update(new: PlayerState) {
        _state.value = new
    }
}
