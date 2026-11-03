package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import kotlin.math.abs

// Regras puras da seleção de trecho no Leitor (rb-30): sem nada de Android, testadas na JVM.
// O menu e as views são do MenuDaSelecaoDeTexto e do ParagrafoAdapter.

/** Trecho marcado dentro de um parágrafo: offsets (fim exclusivo) e o texto, sem espaços nas pontas. */
internal data class TrechoSelecionado(val inicio: Int, val fim: Int, val texto: String)

/**
 * Trecho marcado pela seleção nativa de um TextView. Aceita a seleção invertida (início depois do fim), que o
 * Android pode devolver. Nulo sem seleção. O texto pode sair em branco: cada ação confere.
 */
internal fun trechoSelecionado(selStart: Int, selEnd: Int, texto: CharSequence): TrechoSelecionado? {
    val inicio = minOf(selStart, selEnd)
    val fim = maxOf(selStart, selEnd).coerceAtMost(texto.length)
    if (inicio < 0 || fim <= inicio) return null
    return TrechoSelecionado(inicio, fim, texto.subSequence(inicio, fim).toString().trim())
}

/**
 * Texto dos parágrafos de [inicio] a [fim] (os dois incluídos, em qualquer ordem), separados por linha em branco.
 * Nulo quando a faixa cai fora da lista ou quando todos os parágrafos da faixa estão em branco.
 */
internal fun textoDosParagrafos(paragrafos: List<Paragrafo>, inicio: Int, fim: Int): String? {
    val de = minOf(inicio, fim).coerceAtLeast(0)
    val ate = maxOf(inicio, fim).coerceAtMost(paragrafos.lastIndex)
    if (de > ate) return null
    return paragrafos.subList(de, ate + 1).joinToString("\n\n") { it.texto }.takeIf { it.isNotBlank() }
}

/** Quantos parágrafos a seleção cobre: 0 sem início; com início só, 1. Vale para a barra de seleção. */
internal fun paragrafosNaSelecao(selecaoInicio: Int?, selecaoFim: Int?): Int {
    val inicio = selecaoInicio ?: return 0
    return abs((selecaoFim ?: inicio) - inicio) + 1
}
