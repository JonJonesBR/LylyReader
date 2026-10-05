package com.jonjonesbr.audiobookgen.player

import kotlin.math.pow

/**
 * Ajuste de timbre aplicado na reprodução da leitura guiada (e, nos audiobooks novos, ao final da
 * conversão). [meiosTons] desloca o tom em passos de meio semitom sem mudar a velocidade;
 * [agudosDb] reduz os agudos de forma suave (prateleira), sem corte seco.
 */
data class TimbreVoz(val meiosTons: Int = 0, val agudosDb: Int = 0) {
    val ativo: Boolean get() = meiosTons != 0 || agudosDb != 0

    /** Fator de tom para o player (1,0 = original). Um semitom = 2^(1/12). */
    val fatorDeTom: Float get() = 2.0.pow(meiosTons / 24.0).toFloat()

    val semitons: Float get() = meiosTons / 2f

    companion object {
        val PADRAO = TimbreVoz()
    }
}
