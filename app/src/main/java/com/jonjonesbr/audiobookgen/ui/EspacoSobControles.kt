package com.jonjonesbr.audiobookgen.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Mantém o fim de uma lista acima das barras que flutuam sobre a base dela (ex.: os controles da
 * leitura guiada e o mini-player do leitor). Antes o leitor tinha um respiro fixo de 96dp: a barra da
 * leitura guiada é bem mais alta e cobria as últimas linhas, inclusive o parágrafo em leitura.
 */
object EspacoSobControles {

    private const val MINIMO_DP = 96
    private const val FOLGA_DP = 16

    /** A cada layout, o respiro inferior de [lista] passa a ser o quanto as [barras] visíveis a cobrem. */
    fun manter(lista: RecyclerView, barras: List<View>) {
        val densidade = lista.resources.displayMetrics.density
        val minimo = (MINIMO_DP * densidade).toInt()
        val folga = (FOLGA_DP * densidade).toInt()
        val posLista = IntArray(2)
        val posBarra = IntArray(2)
        lista.viewTreeObserver.addOnGlobalLayoutListener {
            lista.getLocationInWindow(posLista)
            val baseDaLista = posLista[1] + lista.height
            val coberto = barras.filter { it.isShown && it.height > 0 }.maxOfOrNull { barra ->
                barra.getLocationInWindow(posBarra)
                baseDaLista - posBarra[1]
            } ?: 0
            val alvo = maxOf(minimo, coberto + folga)
            // Só mexe quando muda: setPadding pede outro layout, que chama este listener de novo.
            if (lista.paddingBottom != alvo) {
                lista.setPadding(lista.paddingLeft, lista.paddingTop, lista.paddingRight, alvo)
            }
        }
    }
}
