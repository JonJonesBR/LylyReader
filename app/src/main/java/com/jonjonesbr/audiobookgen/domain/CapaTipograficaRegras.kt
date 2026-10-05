package com.jonjonesbr.audiobookgen.domain

/** Fundos das capas tipográficas (livros sem capa própria): cores de lombada, iguais em qualquer tema. */
val PALETA_CAPAS: IntArray = intArrayOf(
    0xFF3B2147.toInt(), 0xFF1F3A34.toInt(), 0xFF5A1F1F.toInt(), 0xFF1E2A44.toInt(),
    0xFF4A3B1F.toInt(), 0xFF2E3B4A.toInt(), 0xFF3F2A2A.toInt(), 0xFF24413A.toInt()
)

/** Cor estável por livro: o mesmo `id` sempre dá a mesma capa. */
fun corDaCapa(id: String): Int = PALETA_CAPAS[(id.hashCode() and Int.MAX_VALUE) % PALETA_CAPAS.size]

/** Títulos longos usam letra menor (fração da largura da capa). */
fun tamanhoRelativoDoTitulo(titulo: String): Float = when {
    titulo.length > 60 -> 0.095f
    titulo.length > 32 -> 0.115f
    else -> 0.14f
}
