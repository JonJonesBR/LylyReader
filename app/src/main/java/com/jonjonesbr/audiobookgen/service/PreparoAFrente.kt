package com.jonjonesbr.audiobookgen.service

/**
 * Regras do "Preparar capítulo" que corre à frente da leitura guiada (rb-25). Puras: o [GuidedPlayerManager] lê o
 * estado da leitura e usa estas funções para decidir o que o preparo sintetiza, pula e espera.
 */
internal object PreparoAFrente {
    /** Parágrafos depois do em leitura que a leitura ao vivo já cobre (prefetch e handoff). */
    const val JANELA_DA_LEITURA = 1

    /**
     * Janela sem leitura ao vivo (pausada, parada ou com erro): só ficam para trás os parágrafos antes do em
     * leitura. O próprio parágrafo em leitura e os seguintes são do preparo, porque nada os está sintetizando agora.
     */
    const val JANELA_SEM_LEITURA = -1

    /** Janela que vale para o estado atual: a leitura ao vivo cobre um parágrafo a mais; sem ela, nenhum. */
    fun janelaDaLeitura(aoVivo: Boolean): Int = if (aoVivo) JANELA_DA_LEITURA else JANELA_SEM_LEITURA

    /**
     * Verdadeiro quando o parágrafo da unidade já está na faixa que a leitura ao vivo cobre: o em leitura, ou até
     * [janela] parágrafos depois. Sem leitura (índice -1), nada ficou para trás: o preparo segue normalmente.
     * Janela negativa ([JANELA_SEM_LEITURA]) cobre só os parágrafos anteriores ao em leitura: o próprio em leitura
     * e os seguintes não ficam para trás.
     */
    fun ficouParaALeitura(
        paragrafoDaUnidade: Int,
        paragrafoEmLeitura: Int,
        janela: Int = JANELA_DA_LEITURA,
    ): Boolean =
        paragrafoEmLeitura >= 0 && paragrafoDaUnidade <= paragrafoEmLeitura + janela

    /**
     * Índice da próxima unidade que o preparo deve sintetizar, a partir de [cursor]: a primeira que a leitura ao
     * vivo ainda não cobre. Devolve o tamanho da lista quando não sobra nada, e nunca volta para antes de [cursor].
     * Sem leitura (índice -1), devolve [cursor] para o preparo começar do jeito de sempre.
     */
    fun proximaUnidade(
        paragrafosDasUnidades: List<Int>,
        cursor: Int,
        paragrafoEmLeitura: Int,
        janela: Int = JANELA_DA_LEITURA,
    ): Int {
        if (paragrafoEmLeitura < 0) return cursor
        val primeiraLivre = (cursor until paragrafosDasUnidades.size).firstOrNull {
            !ficouParaALeitura(paragrafosDasUnidades[it], paragrafoEmLeitura, janela)
        }
        return primeiraLivre ?: maxOf(cursor, paragrafosDasUnidades.size)
    }

    /**
     * Se o preparo deve esperar antes de sintetizar mais uma unidade. Pausada, a leitura não disputa o motor: o preparo
     * segue. Fora da pausa, o preparo espera enquanto a leitura sintetiza (carregando, orações pendentes, handoff ou
     * prefetch em voo), para não tirar do motor a vez que a fala precisa. No motor lento (Kokoro, RTF acima de 1) o
     * preparo também espera enquanto a leitura toca, para não atrasar a fala.
     */
    fun esperaALeitura(motorLento: Boolean, tocando: Boolean, sintetizando: Boolean, pausada: Boolean): Boolean =
        !pausada && (sintetizando || (motorLento && tocando))
}
