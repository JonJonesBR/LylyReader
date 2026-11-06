package com.jonjonesbr.audiobookgen.ui

import android.widget.TextView

/**
 * Onde a seleção está numa página do modo páginas (rb-67/3): o topo da linha do início e a base da linha do fim, nas
 * coordenadas da lista, e quais pontas o arraste mexeu desde a descida anterior.
 */
internal data class SelecaoNaPagina(
    val topoDoInicio: Int,
    val baseDoFim: Int,
    val moveuInicio: Boolean,
    val moveuFim: Boolean,
)

/**
 * Sentido da virada para a seleção (rb-67/3): 1 para a frente se a ponta do fim que o arraste mexeu ficou abaixo do
 * corte; -1 para trás se a ponta do início que ele mexeu ficou acima do topo; 0 se nenhuma saiu da página. A ponta que
 * não se mexeu não conta: uma seleção que começou numa página e já foi para a outra (PAGE_DOWN) não volta para trás por
 * causa do início escondido. Função pura, testada na JVM.
 */
internal fun sentidoParaASelecao(selecao: SelecaoNaPagina, topo: Int, corte: Int): Int = when {
    selecao.moveuFim && selecao.baseDoFim > corte -> 1
    selecao.moveuInicio && selecao.topoDoInicio < topo -> -1
    else -> 0
}

/**
 * Vira a página do modo páginas para a seleção do [tv], quando uma ponta que o arraste mexeu ficou fora da página
 * (rb-67/3). Chamada ao soltar a alça, por post, e nunca durante o arraste: o HandleView mapeia o dedo para o texto que
 * está no lugar, e uma virada no meio dele pularia a seleção. As medidas são as da lista, como em linhasDoParagrafo.
 * Devolve se virou. Sem paginação em uso, sem layout ou sem seleção, não faz nada.
 */
internal fun RecyclerViewParagrafos.virarParaASelecao(tv: TextView, moveuInicio: Boolean, moveuFim: Boolean): Boolean {
    val pagina = paginacao?.takeIf { it.ativa }
    // As medidas abaixo supõem que o TextView é o próprio item (filho direto da lista): de outro jeito, não vira.
    val layout = tv.layout?.takeIf { tv.parent === this }
    if (pagina == null || layout == null || !tv.hasSelection()) return false
    val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
    val selecao = SelecaoNaPagina(
        topoDoInicio = y0 + layout.getLineTop(layout.getLineForOffset(minOf(tv.selectionStart, tv.selectionEnd))),
        baseDoFim = y0 + layout.getLineBottom(layout.getLineForOffset(maxOf(tv.selectionStart, tv.selectionEnd))),
        moveuInicio = moveuInicio,
        moveuFim = moveuFim,
    )
    val sentido = sentidoParaASelecao(selecao, paddingTop, pagina.corte())
    return sentido != 0 && pagina.virarPeloLeitor(sentido)
}
