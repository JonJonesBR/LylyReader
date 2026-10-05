package com.jonjonesbr.audiobookgen.domain

/** Quando a Biblioteca oferece o livro de exemplo (Fase J): só no vazio de verdade, nunca durante uma busca/filtro. */
object LivroDeExemploRegras {
    fun deveOferecer(totalLivros: Int, filtroAtivo: Boolean, exemploPresente: Boolean): Boolean =
        totalLivros == 0 && !filtroAtivo && !exemploPresente
}
