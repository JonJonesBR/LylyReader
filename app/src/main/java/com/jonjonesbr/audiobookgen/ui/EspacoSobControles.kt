package com.jonjonesbr.audiobookgen.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Mantém o fim de uma lista acima das barras que flutuam sobre a base dela (ex.: os controles da
 * leitura guiada e o mini-player do leitor). Antes o leitor tinha um respiro fixo de 96dp: a barra da
 * leitura guiada é bem mais alta e cobria as últimas linhas, inclusive o parágrafo em leitura.
 *
 * rb-26b (passo 3): a barra da seleção, quando fica embaixo no modo páginas, conta no respiro, e o painel da guiada
 * conta mesmo INVISIBLE (cedido à barra). Assim a página não muda de tamanho quando a seleção abre.
 */
object EspacoSobControles {

    private const val MINIMO_DP = 96
    private const val FOLGA_DP = 16

    /**
     * A cada layout, o respiro inferior de [lista] passa a ser o quanto as [barras] cobrem. A lambda é lida a cada
     * layout (a lista de barras pode mudar: a barra da seleção entra só quando está embaixo); as views em si são
     * achadas fora.
     */
    fun manter(lista: RecyclerView, barras: () -> List<View>) {
        val densidade = lista.resources.displayMetrics.density
        val minimo = (MINIMO_DP * densidade).toInt()
        val folga = (FOLGA_DP * densidade).toInt()
        val posLista = IntArray(2)
        val posBarra = IntArray(2)
        lista.viewTreeObserver.addOnGlobalLayoutListener {
            lista.getLocationInWindow(posLista)
            // getLocationInWindow já inclui a translação da lista (rb-48: a barra da seleção a desce). Sem
            // desfazê-la, o respiro cresceria a cada descida, pediria layout e mexeria no parágrafo em leitura.
            val baseDaLista = posLista[1] - lista.translationY.roundToInt() + lista.height
            val toposDasBarras = barras().filter {
                barraContaNoRespiro(it.visibility, it.height, (it.parent as? View)?.isShown != false)
            }.map { barra ->
                barra.getLocationInWindow(posBarra)
                posBarra[1]
            }
            val alvo = respiroSobAsBarras(baseDaLista, toposDasBarras, minimo, folga)
            // Só mexe quando muda: setPadding pede outro layout, que chama este listener de novo.
            if (lista.paddingBottom != alvo) {
                lista.setPadding(lista.paddingLeft, lista.paddingTop, lista.paddingRight, alvo)
            }
        }
    }
}

/**
 * Se uma barra conta no respiro da lista (rb-26b, passo 3): não GONE, com altura e com o pai na tela. INVISIBLE
 * conta: o painel da guiada cedido à barra da seleção continua ocupando o lugar, e a página não pode mudar de tamanho.
 */
internal fun barraContaNoRespiro(visibilidade: Int, altura: Int, paiNaTela: Boolean): Boolean =
    visibilidade != View.GONE && altura > 0 && paiNaTela

/**
 * Respiro inferior da lista (rb-26b, passo 3): a distância da base da lista até o topo da barra mais alta, mais a
 * folga, e nunca menos que o [minimo]. [toposDasBarras] e [baseDaLista] são na janela. Sem barras, dá o mínimo.
 */
internal fun respiroSobAsBarras(baseDaLista: Int, toposDasBarras: List<Int>, minimo: Int, folga: Int): Int =
    maxOf(minimo, (toposDasBarras.maxOfOrNull { baseDaLista - it } ?: 0) + folga)
