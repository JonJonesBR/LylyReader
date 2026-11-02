package com.jonjonesbr.audiobookgen.player

import android.media.audiofx.Equalizer
import android.util.Log
import androidx.annotation.VisibleForTesting

/** Corte suave de agudos com o equalizador do sistema (bandas acima de ~2,5 kHz), numa sessão de áudio. */
class AgudosNoEqualizador {
    private var equalizador: Equalizer? = null

    /** Verdadeiro enquanto há um equalizador criado e ainda não liberado. */
    @VisibleForTesting
    internal val ligado: Boolean get() = equalizador != null

    fun aplicar(sessao: Int, agudosDb: Int) {
        liberar()
        if (agudosDb <= 0 || sessao <= 0) return
        runCatching {
            val eq = Equalizer(0, sessao)
            val faixa = eq.bandLevelRange
            for (banda in 0 until eq.numberOfBands) {
                val centroHz = eq.getCenterFreq(banda.toShort()) / MILI
                nivelDaBandaMb(centroHz, agudosDb, faixa[0].toInt(), faixa[1].toInt())
                    ?.let { eq.setBandLevel(banda.toShort(), it.toShort()) }
            }
            eq.enabled = true
            equalizador = eq
        }.onFailure { Log.w(TAG, "Equalizador indisponível: ${it.message}") }
    }

    fun liberar() {
        runCatching { equalizador?.release() }
        equalizador = null
    }

    internal companion object {
        private const val TAG = "AgudosNoEqualizador"
        private const val MILI = 1000
        private const val AGUDOS_HZ = 8000
        private const val MEDIOS_ALTOS_HZ = 2500
        private const val CENTESIMOS_DE_DB = 100

        /**
         * Nível do corte para uma banda, em milibéis, ou null quando a banda não corta. [centroHz] é o centro
         * da banda em Hz; [minMb] e [maxMb] são a faixa do equalizador (`bandLevelRange`), também em milibéis.
         */
        internal fun nivelDaBandaMb(centroHz: Int, agudosDb: Int, minMb: Int, maxMb: Int): Int? {
            val corteDb = when {
                centroHz >= AGUDOS_HZ -> agudosDb.toDouble()
                centroHz >= MEDIOS_ALTOS_HZ -> agudosDb / 2.0
                else -> 0.0
            }
            if (corteDb <= 0) return null
            return (-corteDb * CENTESIMOS_DE_DB).toInt().coerceIn(minMb, maxMb)
        }
    }
}
