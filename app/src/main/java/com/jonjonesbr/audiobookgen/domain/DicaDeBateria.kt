package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura da dica de otimização de bateria (sem Android).
 * A dica aparece uma única vez, só quando faz diferença: a partir do Android 6 (API 23), com o app
 * ainda sujeito à otimização de bateria e sem que a pessoa já tenha visto a sugestão.
 */
object DicaDeBateria {

    fun deveSugerir(sdk: Int, otimizacaoIgnorada: Boolean, jaVista: Boolean): Boolean =
        sdk >= 23 && !otimizacaoIgnorada && !jaVista
}
