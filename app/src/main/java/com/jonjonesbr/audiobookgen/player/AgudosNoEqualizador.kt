package com.jonjonesbr.audiobookgen.player

import android.media.audiofx.Equalizer
import android.util.Log

/** Corte suave de agudos com o equalizador do sistema (bandas acima de ~2,5 kHz), numa sessão de áudio. */
class AgudosNoEqualizador {
    private var equalizador: Equalizer? = null

    fun aplicar(sessao: Int, agudosDb: Int) {
        liberar()
        if (agudosDb <= 0 || sessao <= 0) return
        runCatching {
            val eq = Equalizer(0, sessao)
            val faixa = eq.bandLevelRange
            for (banda in 0 until eq.numberOfBands) {
                val centroHz = eq.getCenterFreq(banda.toShort()) / MILI
                val corteDb = when {
                    centroHz >= AGUDOS_HZ -> agudosDb.toDouble()
                    centroHz >= MEDIOS_ALTOS_HZ -> agudosDb / 2.0
                    else -> 0.0
                }
                if (corteDb > 0) {
                    val nivel = (-corteDb * CENTESIMOS_DE_DB).toInt().coerceIn(faixa[0].toInt(), faixa[1].toInt())
                    eq.setBandLevel(banda.toShort(), nivel.toShort())
                }
            }
            eq.enabled = true
            equalizador = eq
        }.onFailure { Log.w(TAG, "Equalizador indisponível: ${it.message}") }
    }

    fun liberar() {
        runCatching { equalizador?.release() }
        equalizador = null
    }

    private companion object {
        const val TAG = "AgudosNoEqualizador"
        const val MILI = 1000
        const val AGUDOS_HZ = 8000
        const val MEDIOS_ALTOS_HZ = 2500
        const val CENTESIMOS_DE_DB = 100
    }
}
