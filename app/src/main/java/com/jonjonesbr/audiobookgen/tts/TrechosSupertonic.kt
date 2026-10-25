package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.util.DivisorDeOracoes

/**
 * Regras puras da síntese Supertonic contra palavras e frases puladas.
 *
 * O Supertonic prevê UMA duração para o trecho inteiro e encaixa a fala nesse tempo. Quando a previsão sai
 * curta demais (mais comum em trechos longos), o modelo comprime a fala e pula palavras. Duas defesas:
 * trechos curtos por chamada ([dividir]) e, se o áudio ainda sair rápido demais para o texto, refazer o
 * trecho com mais tempo ([velocidadeParaRefazer]).
 */
object TrechosSupertonic {
    /** Antes eram 500 chars por chamada (o nativo recortava em ~300); trechos longos erram mais a duração. */
    const val MAX_CHARS = 160

    /** Letras por segundo (na velocidade 1.0) acima das quais a fala está comprimida demais. */
    const val LETRAS_POR_SEGUNDO_MAX = 18f

    /** Ritmo de leitura natural (letras por segundo na velocidade 1.0) usado ao refazer. */
    const val LETRAS_POR_SEGUNDO_ALVO = 14f

    /** Trechos curtos demais não dão medida confiável (o silêncio das bordas pesa). */
    private const val MIN_LETRAS = 20
    private const val VELOCIDADE_MIN_RELATIVA = 0.5f
    private val ESPACOS = Regex("\\s+")

    /** Divide [texto] em trechos de até [maxChars], por frase e, se preciso, por oração ou palavra. Não perde texto. */
    fun dividir(texto: String, maxChars: Int = MAX_CHARS): List<String> {
        val trechos = mutableListOf<String>()
        val atual = StringBuilder()
        for (oracao in DivisorDeOracoes.dividirEmOracoes(texto)) {
            for (parte in partir(oracao.texto, maxChars)) {
                if (atual.isNotEmpty() && atual.length + 1 + parte.length > maxChars) {
                    trechos += atual.toString()
                    atual.clear()
                }
                if (atual.isNotEmpty()) atual.append(' ')
                atual.append(parte)
            }
        }
        if (atual.isNotEmpty()) trechos += atual.toString()
        return trechos
    }

    /** Frase maior que o limite: corta depois de vírgula/ponto e vírgula/dois-pontos e, se não houver, entre palavras. */
    private fun partir(frase: String, maxChars: Int): List<String> {
        if (frase.length <= maxChars) return listOf(frase)
        val partes = mutableListOf<String>()
        val atual = StringBuilder()
        for (palavra in frase.split(ESPACOS).filter { it.isNotEmpty() }) {
            if (atual.isNotEmpty() && atual.length + 1 + palavra.length > maxChars) {
                partes += atual.toString()
                atual.clear()
            }
            if (atual.isNotEmpty()) atual.append(' ')
            atual.append(palavra)
            if (palavra.last() in ",;:" && atual.length >= maxChars / 2) {
                partes += atual.toString()
                atual.clear()
            }
        }
        if (atual.isNotEmpty()) partes += atual.toString()
        return partes
    }

    /**
     * Velocidade para refazer [texto] quando o áudio de [segundos] saiu rápido demais na [velocidade] pedida
     * (sinal de que o modelo pulou palavras); null quando o ritmo é plausível.
     */
    fun velocidadeParaRefazer(texto: String, segundos: Float, velocidade: Float): Float? {
        val letras = texto.count { it.isLetterOrDigit() }
        if (segundos <= 0f || velocidade <= 0f || letras < MIN_LETRAS) return null
        val letrasPorSegundoNaVelocidade1 = letras / segundos / velocidade
        return if (letrasPorSegundoNaVelocidade1 <= LETRAS_POR_SEGUNDO_MAX) {
            null
        } else {
            (velocidade * LETRAS_POR_SEGUNDO_ALVO / letrasPorSegundoNaVelocidade1)
                .coerceAtLeast(velocidade * VELOCIDADE_MIN_RELATIVA)
        }
    }
}
