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
