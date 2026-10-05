package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.CapituloAudio

/** Margem (ms): abaixo disso do início do capítulo atual, "anterior" reinicia o capítulo em vez de voltar um. */
private const val MARGEM_REINICIAR_CAPITULO_MS = 3_000L

/** Início (ms) do próximo capítulo após [posicaoMs], ou null se já está no último capítulo. */
fun proximoCapituloMs(capitulos: List<CapituloAudio>, posicaoMs: Long): Long? =
    capitulos.map { it.inicioMs }.filter { it > posicaoMs }.minOrNull()

/**
 * Início (ms) do capítulo anterior, ou null se já está no primeiro (ou lista vazia).
 * Padrão comum de players: se a posição já passou [MARGEM_REINICIAR_CAPITULO_MS] do início do
 * capítulo atual, "anterior" reinicia o capítulo corrente em vez de pular pro de antes.
 */
fun capituloAnteriorMs(capitulos: List<CapituloAudio>, posicaoMs: Long): Long? {
    val inicios = capitulos.map { it.inicioMs }.sorted()
    val indiceAtual = inicios.indexOfLast { it <= posicaoMs }
    if (indiceAtual < 0) return null
    val inicioAtual = inicios[indiceAtual]
    return if (posicaoMs - inicioAtual > MARGEM_REINICIAR_CAPITULO_MS) inicioAtual
    else inicios.getOrNull(indiceAtual - 1)
}
