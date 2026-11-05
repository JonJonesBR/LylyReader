package com.jonjonesbr.audiobookgen.domain

/**
 * Uma linha de texto já medida, em pixels, nas coordenadas da lista (rb-26b). [topo] e [base] são as bordas
 * verticais da linha: a lista de linhas vem em ordem crescente, e entre parágrafos há espaço vazio.
 */
data class LinhaDaPagina(val topo: Int, val base: Int)

/**
 * Regra pura das páginas virtuais do modo "páginas" do Leitor (rb-26b). A página é uma lista vertical de
 * parágrafos com a linha parcial de baixo escondida (clip até a base da última linha inteira), e virar a página
 * é um deslocamento instantâneo. Estas funções só fazem a conta sobre as linhas já medidas. Sem Android.
 */
object RegraDasPaginasDoLeitor {

    /**
     * Até onde a página desenha: a base da última linha que termina em [base] ou antes. Sem nenhuma linha inteira
     * até ali, devolve [base] (a página corta no próprio limite).
     */
    fun corte(linhas: List<LinhaDaPagina>, base: Int): Int =
        linhas.lastOrNull { it.base <= base }?.base ?: base

    /**
     * Deslocamento (com sinal positivo) para virar a página para a frente. Vai até o topo da primeira linha
     * escondida, a que termina depois de [base]. Se essa linha começa em [topo] ou antes (é maior que a página
     * inteira), avança uma altura da janela, para nunca travar. Sem linha escondida (fim do livro), devolve 0.
     */
    fun passoParaFrente(linhas: List<LinhaDaPagina>, topo: Int, base: Int): Int {
        val escondida = linhas.firstOrNull { it.base > base }
        return when {
            escondida == null -> 0
            escondida.topo <= topo -> base - topo
            else -> escondida.topo - topo
        }
    }

    /**
     * Ajuste depois de voltar uma altura (scrollBy negativo): avança até a primeira linha que começa em [topo] ou
     * depois, para o topo cair numa linha inteira. Devolve 0 se a primeira linha já começa no topo ou se não há
     * linhas. Nunca é negativo.
     */
    fun ajusteDepoisDeVoltar(linhas: List<LinhaDaPagina>, topo: Int): Int =
        linhas.firstOrNull { it.topo >= topo }?.let { it.topo - topo } ?: 0

    /**
     * Ajuste do topo quando uma linha cruza a borda superior ([topo] entre o começo e o fim dela): devolve o
     * deslocamento negativo que a mostra inteira, que é o começo da linha menos [topo]. Sem linha cruzada, devolve 0.
     */
    fun ajusteDoTopo(linhas: List<LinhaDaPagina>, topo: Int): Int =
        linhas.firstOrNull { it.topo < topo && topo < it.base }?.let { it.topo - topo } ?: 0

    /**
     * Passo do acompanhamento da leitura guiada. Se a linha narrada termina depois do [corte] (está escondida),
     * vira até ela ficar no topo. Se começa acima do [topo] e [podeSubir] vale, sobe até ela. Nos outros casos
     * não mexe. [podeSubir] falso é a histerese: dentro do mesmo parágrafo a página não sobe (rb-65).
     */
    fun passoDaGuiada(inicioDaLinha: Int, fimDaLinha: Int, topo: Int, corte: Int, podeSubir: Boolean): Int =
        when {
            fimDaLinha > corte -> inicioDaLinha - topo
            inicioDaLinha < topo && podeSubir -> inicioDaLinha - topo
            else -> 0
        }
}
