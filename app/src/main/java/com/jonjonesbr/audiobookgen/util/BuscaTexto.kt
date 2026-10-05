package com.jonjonesbr.audiobookgen.util

/**
 * Resolve a posição de PARÁGRAFO (índice real no RecyclerView) do resultado de busca atual.
 *
 * [resultados] é a lista de parágrafos que contêm o termo buscado (cada entrada já é uma
 * posição de parágrafo); [indiceAtual] é a posição DENTRO dessa lista — "este é o resultado
 * nº 2 de 5" — não a posição do parágrafo em si. Confundir os dois foi um bug real: tanto o
 * highlight do resultado atual quanto o scroll-to comparavam/usavam [indiceAtual] diretamente
 * como se já fosse a posição do parágrafo, fazendo a busca "pular" sempre para os primeiros
 * parágrafos do livro (posição 0, 1, 2…) em vez do parágrafo onde o termo realmente aparece.
 */
fun posicaoResultadoBusca(resultados: List<Int>, indiceAtual: Int): Int? =
    resultados.getOrNull(indiceAtual)
