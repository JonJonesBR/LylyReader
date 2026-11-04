package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura do acompanhamento da leitura guiada (vertical e página de parágrafo longo na horizontal, rb-26a/rb-62).
 * Quanto rolar para manter a linha em leitura perto da altura de destaque. O acompanhamento vertical
 * (GuidedReadingBarController) e o horizontal (PaginaDoParagrafo) usam a mesma regra, para as duas orientações
 * andarem igual. Sem Android.
 */
object RegraDaPaginaDoParagrafo {

    /** Altura de destaque, como fração da área visível da página. */
    const val PROPORCAO_ALVO = 0.42f

    /** Distância mínima, em pixels, para o acompanhamento rolar; abaixo disso a linha já está no lugar. */
    const val LIMIAR_PX = 6

    /** Fração da distância que cada tick de progresso rola, para a rolagem ser suave. */
    const val PROPORCAO_PASSO = 0.18f

    /**
     * Quanto rolar a página (em pixels, com sinal) para a linha em leitura chegar à altura de destaque.
     * [fimDaLinha] é a base da linha medida a partir do topo da área visível (lista ou página). Fora da área
     * (a tela apagada voltou, "Voltar à narração", recomeço do parágrafo) o salto é direto.
     * Dentro dela, a rolagem é em passos.
     */
    fun passoParaDestaque(fimDaLinha: Int, alturaVisivel: Int): Int {
        val distancia = fimDaLinha - (alturaVisivel * PROPORCAO_ALVO).toInt()
        return when {
            alturaVisivel <= 0 -> 0
            fimDaLinha <= 0 || fimDaLinha > alturaVisivel -> distancia
            distancia > LIMIAR_PX -> maxOf(1, (distancia * PROPORCAO_PASSO).toInt())
            else -> 0
        }
    }
}
