package com.jonjonesbr.audiobookgen.tts

/** Quantas threads o motor Pocket usa na síntese: o padrão de sempre, ou (experimental) metade dos núcleos. */
object PocketThreads {
    /** Threads padrão do motor Pocket. */
    const val PADRAO = 2
    private const val DIVISOR_NUCLEOS = 2
    private const val MINIMO_EXPERIMENTAL = 2
    private const val MAXIMO_EXPERIMENTAL = 4

    fun para(nucleos: Int, experimental: Boolean): Int =
        if (experimental) (nucleos / DIVISOR_NUCLEOS).coerceIn(MINIMO_EXPERIMENTAL, MAXIMO_EXPERIMENTAL) else PADRAO
}
