package com.jonjonesbr.audiobookgen.ui

/**
 * Índice do "trecho atual" do Leitor (rb-31): o da narração guiada quando ela tem um (índice >= 0); senão o que
 * [naTela] devolver (o primeiro visível na tela, avaliado só nesse caso, por isso é uma função). Devolve -1 quando
 * não há nenhum dos dois. Usado pelo compartilhar trecho e pela amostra de voz, para os dois seguirem a mesma regra.
 */
internal fun indiceDoTrechoAtual(indiceGuiada: Int?, naTela: () -> Int?): Int =
    indiceGuiada?.takeIf { it >= 0 } ?: naTela() ?: -1
