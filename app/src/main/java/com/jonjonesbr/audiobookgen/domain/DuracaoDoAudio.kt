package com.jonjonesbr.audiobookgen.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Velocidade de leitura de referência, em palavras por minuto (mesma base do `estimar_duracao` do Python). */
private const val PALAVRAS_POR_MINUTO = 150.0

/** Ritmo que corresponde à velocidade normal (100%): o fator de tempo é ritmo / 100. */
private const val RITMO_NORMAL = 100.0

private const val SEGUNDOS_POR_MINUTO = 60.0

/**
 * Duração aproximada do áudio de [palavras] lidas no [ritmo] (%: 100 = normal, 150 = 1,5x mais rápido).
 * Ritmo abaixo de 1% é tratado como 1%, para não dividir por zero. Zero palavras dá zero.
 */
fun estimarDuracaoDoAudio(palavras: Int, ritmo: Int): Duration {
    if (palavras <= 0) return Duration.ZERO
    val palavrasPorMinutoReal = maxOf(PALAVRAS_POR_MINUTO * ritmo.coerceAtLeast(1) / RITMO_NORMAL, 1.0)
    return (palavras * SEGUNDOS_POR_MINUTO / palavrasPorMinutoReal).seconds
}

/** Quantas palavras há no texto: sequências de caracteres sem espaço, somadas em todos os trechos. */
fun contarPalavras(trechos: Iterable<String>): Int {
    var palavras = 0
    for (trecho in trechos) {
        var dentroDePalavra = false
        for (caractere in trecho) {
            if (caractere.isWhitespace()) {
                dentroDePalavra = false
            } else if (!dentroDePalavra) {
                dentroDePalavra = true
                palavras++
            }
        }
    }
    return palavras
}
