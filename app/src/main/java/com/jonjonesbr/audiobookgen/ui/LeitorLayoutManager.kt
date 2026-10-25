package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LinearLayoutManager do leitor que, enquanto [seguindoLeitura] for verdadeiro, ignora os pedidos de
 * "mostrar este trecho" vindos dos parágrafos.
 *
 * Os parágrafos são TextViews selecionáveis: o que o usuário tocou (para começar a leitura dali ou
 * selecionar uma palavra) fica com o foco. A leitura guiada repinta o parágrafo atual ~20x/s, e cada
 * troca de texto num TextView focado pede à lista para deixar o cursor visível, o que puxava a tela
 * para cima; logo depois o acompanhamento empurrava para baixo de novo. Era o "tremor" que só aparecia
 * às vezes (dependia de haver um parágrafo com foco). Durante a leitura guiada quem rola é o acompanhamento.
 */
class LeitorLayoutManager(
    context: Context,
    orientation: Int,
    private val seguindoLeitura: () -> Boolean
) : LinearLayoutManager(context, orientation, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean =
        if (seguindoLeitura()) false
        else super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)

    /** True = "já tratei": impede a lista de rolar até o parágrafo que ganhou foco durante a leitura guiada. */
    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean = seguindoLeitura() || super.onRequestChildFocus(parent, state, child, focused)
}
