package com.jonjonesbr.audiobookgen.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import kotlin.math.roundToInt

/**
 * Mantém o fim de uma lista acima das barras que flutuam sobre a base dela (ex.: os controles da
 * leitura guiada e o mini-player do leitor). Antes o leitor tinha um respiro fixo de 96dp: a barra da
 * leitura guiada é bem mais alta e cobria as últimas linhas, inclusive o parágrafo em leitura.
 *
 * rb-26b (passo 3): a barra da seleção, quando fica embaixo no modo páginas, conta no respiro, e o painel da guiada
 * conta mesmo INVISIBLE (cedido à barra). Assim a página não muda de tamanho quando a seleção abre.
 *
 * rb-26b (passo 7): no modo páginas a reserva de 96dp some; o respiro é só o que as barras cobrem mais a folga.
 */
object EspacoSobControles {

    private const val MINIMO_DP = 96
    internal const val FOLGA_DP = 16

    /**
     * A cada layout, o respiro inferior de [lista] passa a ser o quanto as [barras] cobrem. A lambda é lida a cada
     * layout (a lista de barras pode mudar: a barra da seleção entra só quando está embaixo); as views em si são
     * achadas fora. [reservaMinima] é o espaço mínimo de baixo, em px a partir da base (rb-67, item 18b), e já leva a
     * folga: o respiro não fica menor que ela.
     */
    fun manter(lista: RecyclerView, barras: () -> List<View>, reservaMinima: () -> Int = { 0 }) {
        val densidade = lista.resources.displayMetrics.density
        val minimoVertical = (MINIMO_DP * densidade).toInt()
        val folga = (FOLGA_DP * densidade).toInt()
        val posLista = IntArray(2)
        val posBarra = IntArray(2)
        // Quantas barras contaram no layout anterior. O fixador só vale quando a conta não muda (o painel que aparece
        // ou a seleção que cede mudam a conta e seguem o setPadding de sempre).
        var barrasNoUltimoLayout = -1
        lista.viewTreeObserver.addOnGlobalLayoutListener {
            // Lido a cada layout: o modo páginas liga e desliga com a leitura.
            val paginas = (lista as? RecyclerViewParagrafos)?.paginacao?.ativa == true
            val minimo = minimoDoRespiro(paginas, minimoVertical, folga)
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
            val mesmasBarras = toposDasBarras.isNotEmpty() && toposDasBarras.size == barrasNoUltimoLayout
            barrasNoUltimoLayout = toposDasBarras.size
            val alvo = maxOf(respiroSobAsBarras(baseDaLista, toposDasBarras, minimo, folga), reservaMinima())
            // Só mexe quando muda: setPadding pede outro layout, que chama este listener de novo.
            if (lista.paddingBottom != alvo) {
                val mudar = { lista.setPadding(lista.paddingLeft, lista.paddingTop, lista.paddingRight, alvo) }
                val paginaDaLista = lista as? RecyclerViewParagrafos
                // rb-66 (passo 5): quando o espaço sob os controles encolhe (o respiro de baixo cresce, p.ex. o painel
                // abre), o LinearLayoutManager sobe o texto pela âncora do parágrafo focado. Com a lista parada e as
                // mesmas barras, a 1ª linha fica no lugar (manterAPrimeiraLinha). Fora disso, como antes.
                // rb-67 (item 18a): vale nos dois modos. No vertical a âncora do foco fazia o mesmo, subindo ~55 dp.
                // Um salto que acabou de terminar o layout (rb-66, passo 5) já alinhou o topo: o fixador não roda
                // por cima.
                val espacoEncolheu = alvo > lista.paddingBottom
                val listaParada = lista.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    (lista.layoutManager as? LeitorLayoutManager)?.saltoNoUltimoLayout != true
                val segurarALinha = espacoEncolheu && listaParada
                if (segurarALinha && mesmasBarras && paginaDaLista != null) {
                    paginaDaLista.manterAPrimeiraLinha(mudar)
                } else {
                    mudar()
                }
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
 * Mínimo do respiro da lista (rb-26b, passo 7). No modo vertical, a reserva de [minimoVertical] fica para o fim do
 * capítulo subir acima do FAB e dos controles. No modo páginas a página acaba em `altura - paddingBottom`, e a
 * reserva só comeria o texto: sobra a [folga], e as barras que estão embaixo entram pelo próprio respiro.
 */
internal fun minimoDoRespiro(paginasAtivas: Boolean, minimoVertical: Int, folga: Int): Int =
    if (paginasAtivas) folga else minimoVertical

/**
 * Respiro inferior da lista (rb-26b, passo 3): a distância da base da lista até o topo da barra mais alta, mais a
 * folga, e nunca menos que o [minimo]. [toposDasBarras] e [baseDaLista] são na janela. Sem barras, dá o mínimo.
 */
internal fun respiroSobAsBarras(baseDaLista: Int, toposDasBarras: List<Int>, minimo: Int, folga: Int): Int =
    maxOf(minimo, (toposDasBarras.maxOfOrNull { baseDaLista - it } ?: 0) + folga)

/**
 * Reserva de baixo que deixa a última linha da página encostar [folga] px acima do topo da barra da seleção embaixo
 * (rb-67, item 18b), em pixels na janela: a distância da [baseDaLista] até o topo da barra mais a folga. A barra fica
 * encostada na [fundoDaArea], com a [margemDeBaixo] do mini player por baixo dela. A folga é a mesma que toda barra
 * recebe no respiro de [respiroSobAsBarras], então a reserva só passa dele quando a barra é mais alta que a tira.
 */
internal fun reservaAteABarra(
    baseDaLista: Int,
    fundoDaArea: Int,
    margemDeBaixo: Int,
    alturaDaBarra: Int,
    folga: Int,
): Int = baseDaLista - (fundoDaArea - margemDeBaixo - alturaDaBarra) + folga

/**
 * Reserva da barra da seleção embaixo, no modo páginas com a tira na base (rb-67, item 18b). A página reserva a barra
 * mesmo com a seleção fechada: assim ela não muda de tamanho quando a seleção abre, e a linha selecionada nunca fica
 * coberta. A reserva soma a folga, como o respiro de cada barra, e vale quando a barra passa da tira. Mede a barra
 * pela largura da área, porque ela pode não ter layout (GONE) quando a conta é feita. O respiro já conta a tira, e o
 * máximo entre os dois fica.
 */
internal fun reservaDaBarraDaSelecao(barra: View, lista: View): Int {
    val area = barra.parent as? View ?: return 0
    // Barra visível e já desenhada na largura da área: a altura real serve. Medir de novo aqui, a cada layout da
    // janela, mexeria no measuredHeight que o pai usa no próprio layout.
    val alturaDaBarra = if (barra.visibility == View.VISIBLE && barra.isLaidOut && barra.width == area.width) {
        barra.height
    } else {
        barra.measure(
            View.MeasureSpec.makeMeasureSpec(area.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        barra.measuredHeight
    }
    val posLista = IntArray(2).also { lista.getLocationInWindow(it) }
    val baseDaLista = posLista[1] - lista.translationY.roundToInt() + lista.height
    val posArea = IntArray(2).also { area.getLocationInWindow(it) }
    val fundoDaArea = posArea[1] + area.height
    val miniPlayer = barra.rootView.findViewById<View>(R.id.miniPlayerRoot)
        ?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
    val posMini = IntArray(2).also { miniPlayer?.getLocationInWindow(it) }
    val topoDoMiniPlayer = miniPlayer?.let { posMini[1] }
    val margem = lugarDaBarraDaSelecao(true, fundoDaArea, topoDoMiniPlayer).margemDeBaixo
    // A mesma conta de px da folga que o manter faz (densidade da lista).
    val folga = (EspacoSobControles.FOLGA_DP * lista.resources.displayMetrics.density).toInt()
    return reservaAteABarra(baseDaLista, fundoDaArea, margem, alturaDaBarra, folga)
}
