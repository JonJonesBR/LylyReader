package com.jonjonesbr.audiobookgen.domain

/** Números do resumo da aba Audiobooks: a biblioteca inteira, sem o filtro da busca. */
data class ResumoBiblioteca(
    val total: Int,
    val duracaoMs: Long,
    val concluidos: Int
) {
    /** "ouvidos até o fim" só entra no resumo quando há algum concluído. */
    val mostrarConcluidos: Boolean get() = concluidos > 0
}

object ResumoBibliotecaRegras {

    /**
     * Soma a duração e conta os concluídos. Concluído é quem chega a [limiteConcluido] por cento
     * ouvidos (o mesmo limite usado pela ordem "não concluídos primeiro").
     */
    fun <T> calcular(
        itens: List<T>,
        duracaoMs: (T) -> Long,
        progressoPct: (T) -> Int,
        limiteConcluido: Int
    ): ResumoBiblioteca = ResumoBiblioteca(
        total = itens.size,
        duracaoMs = itens.sumOf { duracaoMs(it) },
        concluidos = itens.count { progressoPct(it) >= limiteConcluido }
    )
}
