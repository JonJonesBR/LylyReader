package com.jonjonesbr.audiobookgen.util

/** Abaixo disso do começo, retomar não vale a pena: começa do início. */
const val MARGEM_RETOMADA_MS = 3_000
/** A menos disso do fim, o livro é tratado como terminado e recomeça do início. */
const val MARGEM_FIM_RETOMADA_MS = 10_000

/**
 * Onde o audiobook continua ao ser aberto: a posição salva menos o recuo do auto-rewind, ou 0 (começo) quando não há
 * posição salva, ela está quase no início ou quase no fim do arquivo.
 */
fun posicaoDeRetomada(salvaMs: Int?, duracaoMs: Int, recuoMs: Int): Int {
    if (salvaMs == null || salvaMs !in MARGEM_RETOMADA_MS until (duracaoMs - MARGEM_FIM_RETOMADA_MS)) return 0
    return (salvaMs - recuoMs).coerceAtLeast(0)
}
