package com.jonjonesbr.audiobookgen.domain

/**
 * Quantos parágrafos a leitura guiada prepara à frente do que está tocando (Supertonic e Pocket).
 * Função pura: o GuidedPlayerManager decide com ela se vale preparar mais um parágrafo.
 */
object PlanoDeAntecipacao {
    /** Áudio já pronto à frente (contando o que falta do parágrafo atual) até o qual vale preparar mais um. */
    const val ORCAMENTO_AUDIO_A_FRENTE_S = 25f

    /** Máximo de parágrafos preparados à frente, sem contar o que está tocando. */
    const val LIMITE_PARAGRAFOS_A_FRENTE = 4

    /** Ritmo de fala usado só quando a duração do WAV não pode ser lida. */
    const val LETRAS_POR_SEGUNDO = 10f

    /**
     * Vale preparar mais um parágrafo? Sempre pelo menos o próximo (como antes da antecipação), mesmo que o
     * restante do atual já passe do orçamento. Depois disso, para ao atingir o limite de parágrafos ou o
     * orçamento de áudio.
     */
    fun devePreparar(
        segundosProntos: Float,
        paragrafosProntos: Int,
        orcamentoS: Float = ORCAMENTO_AUDIO_A_FRENTE_S,
        limite: Int = LIMITE_PARAGRAFOS_A_FRENTE
    ): Boolean = paragrafosProntos == 0 || (paragrafosProntos < limite && segundosProntos < orcamentoS)

    /** Duração estimada de um texto de [letras] caracteres, para quando o WAV não pode ser lido. */
    fun estimarSegundosPorLetras(letras: Int): Float = letras / LETRAS_POR_SEGUNDO
}
