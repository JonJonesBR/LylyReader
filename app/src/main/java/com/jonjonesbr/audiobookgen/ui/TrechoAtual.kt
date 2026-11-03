package com.jonjonesbr.audiobookgen.ui

/**
 * Índice do "trecho atual" do Leitor (rb-31): o da narração guiada quando ela tem um (índice >= 0); senão o que
 * [naTela] devolver (o primeiro visível na tela, avaliado só nesse caso, por isso é uma função). Devolve -1 quando
 * não há nenhum dos dois. Usado pelo compartilhar trecho e pela amostra de voz, para os dois seguirem a mesma regra.
 *
 * rb-34: se a pessoa rolou para longe da narração ([rolouParaLonge], o mesmo estado do botão "Voltar à
 * narração"), vale o que está na tela; só sem tela cai no da guiada.
 */
internal fun indiceDoTrechoAtual(indiceGuiada: Int?, rolouParaLonge: Boolean, naTela: () -> Int?): Int {
    val guiada = indiceGuiada?.takeIf { it >= 0 }
    return if (rolouParaLonge) naTela() ?: guiada ?: -1 else guiada ?: naTela() ?: -1
}
