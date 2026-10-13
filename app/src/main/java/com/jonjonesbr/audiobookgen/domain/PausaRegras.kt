package com.jonjonesbr.audiobookgen.domain

/**
 * Regras puras da conversão pausada. Sem Android.
 * Só há um job pausado guardado por vez: iniciar outra conversão com ele guardado sobrescreveria o job pausado.
 */
object PausaRegras {

    fun podeIniciarNovaConversao(existeJobPausado: Boolean): Boolean = !existeJobPausado
}
