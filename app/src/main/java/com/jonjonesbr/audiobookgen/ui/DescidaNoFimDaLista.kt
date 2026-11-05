package com.jonjonesbr.audiobookgen.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Espaço que a descida da barra da seleção (rb-48) põe depois do último parágrafo (rb-51). Fica numa ItemDecoration, e
 * não no paddingBottom da lista: o padding muda o espaço útil, e o LinearLayoutManager sobe o texto para manter o
 * parágrafo focado visível (o que começa abaixo do topo subia até a descida inteira). Decoração não entra nessa conta,
 * então a âncora fica onde está.
 */
internal class EspacoDaDescidaNoFim(private val lista: RecyclerViewParagrafos) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getChildAdapterPosition(view) == state.itemCount - 1) out.bottom = lista.descidaNoFim()
    }
}

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
