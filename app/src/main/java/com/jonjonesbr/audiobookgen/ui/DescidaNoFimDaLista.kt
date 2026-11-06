package com.jonjonesbr.audiobookgen.ui

/** Descida que a lista leva para o fim (rb-51). Na rolagem horizontal é 0: a página tem a altura da área. */
internal fun RecyclerViewParagrafos.descidaNoFim(): Int =
    descidaNoFimPx(descidaDaSelecao, layoutManager?.canScrollVertically() == true)

/**
 * Quanto a lista ainda pode rolar até o fim do último parágrafo, contando o espaço da descida (rb-51). Zero ou menos
 * quando o fim já está na área útil. Nulo sem o último parágrafo na tela. Função de topo, pelo limite de funções da
 * BarraDaSelecaoDeTexto.
 */
internal fun RecyclerViewParagrafos.restanteAteOFim(): Int? {
    val lm = layoutManager ?: return null
    return adapter?.let { lm.findViewByPosition(it.itemCount - 1) }?.let { ultimo ->
        ultimo.bottom + lm.getBottomDecorationHeight(ultimo) - (height - paddingBottom)
    }
}

/**
 * Quanto da descida sai junto com o empurrão do fixLayoutEndGap na volta (rb-51). Esse empurrão cabe no restante até o
 * fim, e não desce mais que a lista rolou desde o topo ([rolagemDoTopo]): sem isso, num capítulo curto que só rola por
 * causa da descida, o texto saltava. Sem o último parágrafo na tela ([restanteAteOFim] nulo), nada sai junto.
 */
internal fun quantoSaiJuntoNaVolta(descida: Int, restanteAteOFim: Int?, rolagemDoTopo: Int): Int {
    if (restanteAteOFim == null) return 0
    val teto = minOf(descida, rolagemDoTopo).coerceAtLeast(0)
    return (descida - restanteAteOFim).coerceIn(0, teto)
}

/**
 * Descida que vai para o fim da lista (rb-51): o alvo da descida, sem negativo, e só na rolagem vertical. Função pura,
 * testada na JVM.
 */
internal fun descidaNoFimPx(descidaPx: Int, rolagemVertical: Boolean): Int =
    if (rolagemVertical) descidaPx.coerceAtLeast(0) else 0

/**
 * Sobe a lista de [descidaAntes] até [alvo] quando a descida diminui (rb-67/7), no mesmo post que avisa o espaço do
 * fim. Encolher esse espaço faz o fim da lista empurrar o texto para baixo no quadro seguinte (o fixLayoutEndGap). A
 * parte da volta que cabe nesse empurrão sai da translação sem animação, no mesmo quadro; o resto anima. Sem isso, o
 * texto descia e subia de novo (o quique). A conta é a mesma do esconder ([quantoSaiJuntoNaVolta]).
 */
internal fun RecyclerViewParagrafos.subirComOFim(descidaAntes: Int, alvo: Int, duracaoMs: Long) {
    val junto = quantoSaiJuntoNaVolta(descidaAntes - alvo, restanteAteOFim(), computeVerticalScrollOffset())
    animate().cancel()
    if (junto > 0) translationY = (translationY - junto).coerceAtLeast(alvo.toFloat())
    if (translationY != alvo.toFloat()) animate().translationY(alvo.toFloat()).setDuration(duracaoMs).start()
}
