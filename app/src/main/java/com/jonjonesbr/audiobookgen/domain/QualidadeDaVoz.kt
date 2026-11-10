package com.jonjonesbr.audiobookgen.domain

/** Passos do Supertonic no preset Rápida (8): síntese mais curta, menos fiel. */
private const val SUPERTONIC_RAPIDA = 8
/** Passos do Supertonic no preset Equilibrada (10), o padrão. */
private const val SUPERTONIC_EQUILIBRADA = 10
/** Passos do Supertonic no preset Alta (14): síntese mais lenta. */
private const val SUPERTONIC_ALTA = 14
/** Passos do Pocket no preset Rápida (6). */
private const val POCKET_RAPIDA = 6
/** Passos do Pocket no preset Equilibrada (10), o padrão. */
private const val POCKET_EQUILIBRADA = 10
/** Passos do Pocket no preset Alta (12): acima de 10 a leitura pode pausar em aparelhos lentos. */
private const val POCKET_ALTA = 12

/**
 * Qualidade da voz na leitura guiada: um seletor (Rápida / Equilibrada / Alta) que grava, por trás, os passos
 * de decodificação do Supertonic e do Pocket. Regra pura: o seletor mostra o preset mais próximo do valor
 * gravado, e um valor fora dos presets não é regravado ao abrir a tela.
 */
enum class QualidadeDaVoz(val supertonicPassos: Int, val pocketPassos: Int) {
    RAPIDA(SUPERTONIC_RAPIDA, POCKET_RAPIDA),
    EQUILIBRADA(SUPERTONIC_EQUILIBRADA, POCKET_EQUILIBRADA),
    ALTA(SUPERTONIC_ALTA, POCKET_ALTA);

    /** Só a Alta avisa que a leitura pode pausar em aparelhos lentos. */
    val avisaPausar: Boolean get() = this == ALTA

    companion object {
        /** Preset cujo passo do Supertonic está mais perto de [passos]; empate vai para o menor (ver [entries]). */
        fun maisProximaDeSupertonic(passos: Int): QualidadeDaVoz =
            entries.minBy { kotlin.math.abs(it.supertonicPassos - passos) }

        /** Preset cujo passo do Pocket está mais perto de [passos]; empate vai para o menor. */
        fun maisProximaDePocket(passos: Int): QualidadeDaVoz =
            entries.minBy { kotlin.math.abs(it.pocketPassos - passos) }

        /** Preset mostrado para o [motor] (`"pocket"` usa o Pocket; os demais, o Supertonic). */
        fun exibida(motor: String, supertonicPassos: Int, pocketPassos: Int): QualidadeDaVoz =
            if (motor == "pocket") maisProximaDePocket(pocketPassos) else maisProximaDeSupertonic(supertonicPassos)
    }
}
