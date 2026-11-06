package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura do acompanhamento da leitura guiada (rb-26a/rb-62). Quanto rolar para manter a linha em leitura perto da
 * altura de destaque. O acompanhamento (GuidedReadingBarController) usa esta regra na lista vertical. Sem Android.
 *
 * rb-65: dentro do mesmo parágrafo a lista só desce; subir (rb-62) só no primeiro acompanhamento depois de o
 * parágrafo mudar. Ver [passoComHisterese].
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
     * [fimDaLinha] é a base da linha medida a partir do topo da área visível da lista. Fora da área
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

    /**
     * rb-65: dentro do mesmo parágrafo o acompanhamento só desce; subir (rb-62) só no primeiro acompanhamento depois de
     * o parágrafo mudar ([podeSubir]). Sem isso, um progresso que volta (a frase seguinte do modo oração, um
     * getCurrentPosition antigo) faz a lista subir e descer. Descer segue igual à [passoParaDestaque].
     */
    fun passoComHisterese(fimDaLinha: Int, alturaVisivel: Int, podeSubir: Boolean): Int {
        val passo = passoParaDestaque(fimDaLinha, alturaVisivel)
        return if (podeSubir) passo else passo.coerceAtLeast(0)
    }
}
