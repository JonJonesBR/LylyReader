package com.jonjonesbr.audiobookgen.domain

/**
 * Atraso que o player impõe a cada unidade da leitura guiada: o tempo que o trecho levou a mais que a duração do
 * arquivo (o MediaPlayer novo, o prepare, o start). A estimativa é a mediana das últimas medidas válidas, e é ela
 * que sai das pausas planejadas, para o silêncio entre unidades ficar perto do valor sorteado. Puro, sem Android:
 * a medição é feita no GuidedPlayerManager.
 */
class EstimativaDeAtraso {

    private val amostras = ArrayDeque<Long>()

    /** Quantas medidas válidas estão guardadas (no máximo [JANELA]). */
    val quantidade: Int get() = amostras.size

    /** Acrescenta uma medida. Valores negativos ou acima de [LIMITE_AMOSTRA_MS] são descartados. */
    fun add(amostraMs: Long) {
        if (amostraMs < 0L || amostraMs > LIMITE_AMOSTRA_MS) return
        amostras.addLast(amostraMs)
        if (amostras.size > JANELA) amostras.removeFirst()
    }

    /** Atraso a descontar, em ms: 0 enquanto houver menos de [MIN_AMOSTRAS]; depois a mediana, até [TETO_MS]. */
    fun estimativaMs(): Long {
        if (amostras.size < MIN_AMOSTRAS) return 0L
        val ordenadas = amostras.sorted()
        val meio = ordenadas.size / 2
        val mediana = if (ordenadas.size % 2 == 0) (ordenadas[meio - 1] + ordenadas[meio]) / 2L else ordenadas[meio]
        return mediana.coerceIn(0L, TETO_MS)
    }

    fun zerar() {
        amostras.clear()
    }

    companion object {
        const val JANELA = 8
        const val MIN_AMOSTRAS = 3
        const val TETO_MS = 600L
        const val LIMITE_AMOSTRA_MS = 1500L
    }
}
