package com.jonjonesbr.audiobookgen.domain

import kotlin.random.Random

/**
 * Pausas da leitura guiada que imitam um narrador humano: a duração depende de como o trecho termina
 * (vírgula, ponto, pergunta, reticências), cresce depois de frases longas (o fôlego) e no fim do parágrafo,
 * e varia um pouco a cada vez, para a narração não soar mecânica. Regras puras: o sorteio entra por [Random].
 *
 * Os valores são para a velocidade 1.0; com a leitura acelerada as pausas encurtam na mesma proporção.
 */
object PausasNaturais {

    /** Faixa de uma pausa, em ms na velocidade 1.0. */
    private data class Faixa(val minimo: Int, val maximo: Int)

    private val RETICENCIAS = Faixa(650, 1050)
    private val PERGUNTA_OU_EXCLAMACAO = Faixa(450, 750)
    private val PONTO = Faixa(380, 650)
    private val DOIS_PONTOS = Faixa(280, 450)
    private val VIRGULA = Faixa(150, 300)
    private val SEM_PONTUACAO = Faixa(250, 400)
    private val FIM_DE_PARAGRAFO = Faixa(800, 1300)
    /** Parágrafo curto (uma fala de diálogo, por exemplo): a troca é mais rápida. */
    private val FIM_DE_PARAGRAFO_CURTO = Faixa(500, 850)
    /** Respiro a mais depois de frase longa. */
    private val FOLEGO = Faixa(80, 220)

    private const val FRASE_LONGA_CHARS = 140
    private const val PARAGRAFO_CURTO_CHARS = 60
    private const val VELOCIDADE_MIN = 0.5f
    private const val VELOCIDADE_MAX = 2f
    private const val FECHAMENTOS = "\"'”’»)]—–- "

    /** Pausa depois de um trecho dentro do parágrafo (frase ou oração). */
    fun aposTrecho(texto: String, velocidade: Float = 1f, aleatorio: Random = Random.Default): Int {
        val base = sortear(faixaDoFinal(texto), aleatorio)
        val folego = if (texto.trim().length > FRASE_LONGA_CHARS) sortear(FOLEGO, aleatorio) else 0
        return escalar(base + folego, velocidade)
    }

    /** Pausa no fim do parágrafo, depois do seu último trecho. */
    fun aposParagrafo(textoDoParagrafo: String, velocidade: Float = 1f, aleatorio: Random = Random.Default): Int {
        val faixa = if (textoDoParagrafo.trim().length < PARAGRAFO_CURTO_CHARS) FIM_DE_PARAGRAFO_CURTO else FIM_DE_PARAGRAFO
        val doFinal = sortear(faixaDoFinal(textoDoParagrafo), aleatorio)
        return escalar(maxOf(sortear(faixa, aleatorio), doFinal), velocidade)
    }

    private fun faixaDoFinal(texto: String): Faixa {
        val semFechamentos = texto.trimEnd { it in FECHAMENTOS }
        return when {
            semFechamentos.endsWith("…") || semFechamentos.endsWith("...") -> RETICENCIAS
            semFechamentos.endsWith("?") || semFechamentos.endsWith("!") -> PERGUNTA_OU_EXCLAMACAO
            semFechamentos.endsWith(".") -> PONTO
            semFechamentos.endsWith(":") || semFechamentos.endsWith(";") -> DOIS_PONTOS
            semFechamentos.endsWith(",") -> VIRGULA
            else -> SEM_PONTUACAO
        }
    }

    /** Média de dois sorteios: valores do meio da faixa saem mais que os extremos, como na fala real. */
    private fun sortear(faixa: Faixa, aleatorio: Random): Int {
        val fracao = (aleatorio.nextFloat() + aleatorio.nextFloat()) / 2f
        return faixa.minimo + ((faixa.maximo - faixa.minimo) * fracao).toInt()
    }

    private fun escalar(ms: Int, velocidade: Float): Int =
        (ms / velocidade.coerceIn(VELOCIDADE_MIN, VELOCIDADE_MAX)).toInt()
}
