package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de parágrafos do leitor. Existe para três coisas:
 *
 * - saber quando o TalkBack pede uma rolagem (ações de acessibilidade SCROLL_FORWARD/BACKWARD): o RecyclerView não
 *   avisa isso, e a leitura guiada precisa tratar essa rolagem como manual, igual ao arraste com o dedo. A rolagem
 *   automática do código não passa por aqui. Na rolagem horizontal (rb-26a), também avisa quando quem lê rola o
 *   texto dentro de uma página de parágrafo longo.
 * - saber quando há um toque em andamento (do DOWN ao UP/CANCEL) ou um menu de seleção aberto (rb-38). Enquanto
 *   isso, [textoEmUso] é verdadeiro e o adapter segura as repinturas de sincronia, que trocariam a view sob o dedo.
 * - rearmar a seleção de texto de cada parágrafo quando a view é anexada à janela (rb-38, ver [rearmarSelecao]).
 * - rodar, depois do toque, a descida que a barra da seleção pede quando cobre o trecho marcado (rb-48, ver
 *   [depoisDoToque]). A descida é translação, não rolagem: não dispara os listeners de rolagem.
 * - ignorar o pedido de mostrar trecho de um parágrafo em foco, no modo de toque, sem seleção (rb-55, ver
 *   [pedidoDeMostrarTrechoVale]); mesmo mecanismo da `PaginaDoParagrafo.requestChildRectangleOnScreen`.
 */
class RecyclerViewParagrafos @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    /** Chamado antes de a rolagem pedida pela acessibilidade acontecer. */
    var aoRolagemDeAcessibilidade: (() -> Unit)? = null

    /** Quem lê rolou o texto dentro de uma página (rolagem horizontal, rb-26a). */
    var aoRolarDentroDaPagina: (() -> Unit)? = null

    /** Chamado (por post) quando o texto deixa de estar em uso: o adapter aplica as repinturas seguradas. */
    var aoLiberarTexto: (() -> Unit)? = null

    /** Chamado (por post, antes de [aoLiberarTexto]) para conferir a seleção viva e mostrar ou esconder a barra. */
    var aoConferirSelecao: (() -> Unit)? = null

    /** Há dedo na lista, do ACTION_DOWN até o UP ou o CANCEL. */
    var toqueEmAndamento = false
        private set

    /** Ação da barra da seleção que espera o toque terminar (rb-48). Só guarda a última. */
    private var acaoDepoisDoToque: (() -> Unit)? = null

    /** Filho da lista que estava sob o dedo no ACTION_DOWN (rb-53). Nulo sem toque. */
    private var filhoSobODedo: View? = null

    /**
     * Se [v] é o item que o dedo tocou, enquanto o toque dura (rb-53). O adapter não reescreve o texto desse item
     * nesse meio-tempo: trocar o texto sob o dedo faz o Editor perder a posição do toque longo.
     */
    fun sobODedo(v: View): Boolean = toqueEmAndamento && filhoSobODedo === v

    /** Quantos menus de seleção de texto estão abertos agora (o adapter conta). */
    var selecoesAbertas = 0
        private set

    /**
     * Verdadeiro enquanto há toque, menu de seleção ou texto selecionado com foco: as repinturas de sincronia
     * esperam. A regra é larga (rb-54): vale para qualquer TextView com foco, inclusive o campo da edição e o texto
     * da comparação de alterações, cuja seleção também não pode ser trocada sob o dedo.
     * O texto selecionado cobre o intervalo entre o UP do toque longo e a abertura do menu (o Editor a abre depois).
     */
    val textoEmUso: Boolean
        get() = segurarRepinturaDeSincronia(toqueEmAndamento, selecoesAbertas, focoComSelecao)

    /**
     * Seleção de texto agora, com a regra larga de [textoEmUso]: menu aberto, ou texto já marcado com foco (antes de
     * o menu abrir). Usada no toque. Quem decide a barra da seleção usa [textoComSelecaoViva], que é mais restrita.
     */
    val selecaoDeTextoAberta: Boolean
        get() = selecoesAbertas > 0 || focoComSelecao

    /** Algum TextView com foco tem texto selecionado. Seleção sem foco não conta: o foco que sai a desfaz. */
    private val focoComSelecao: Boolean
        get() = (findFocus() as? TextView)?.hasSelection() == true

    /**
     * Seleção viva da barra (rb-54): o TextView de leitura com foco e com seleção. Seleção sem foco não conta: o
     * foco que sai a desfaz (ver ParagrafoAdapter.VHLeitura). Só o parágrafo de leitura entra, o que liga o
     * [MenuDaSelecaoDeTexto]: a edição e a comparação de alterações usam o menu do sistema, e a barra do
     * Leitor não é delas. Nulo sem seleção viva.
     */
    internal fun textoComSelecaoViva(): TextView? = (findFocus() as? TextView)?.takeIf {
        it.hasSelection() && it.customSelectionActionModeCallback is MenuDaSelecaoDeTexto
    }

    /**
     * Roda [acao] quando o toque em andamento terminar (rb-48). Sem toque, roda já. Assim a lista não muda de
     * posição sob o dedo: o toque longo abre o menu com o dedo ainda na tela, e um arraste depois dele
     * selecionaria as linhas erradas. Se mais de uma ação chegar no mesmo toque, vale a última.
     */
    fun depoisDoToque(acao: () -> Unit) {
        if (toqueEmAndamento) acaoDepoisDoToque = acao else acao()
    }

    fun abriuSelecao() {
        selecoesAbertas++
    }

    fun fechouSelecao() {
        selecoesAbertas = (selecoesAbertas - 1).coerceAtLeast(0)
        liberarDepois()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            toqueEmAndamento = true
            filhoSobODedo = findChildViewUnder(ev.x, ev.y)
        }
        val tratado = super.dispatchTouchEvent(ev)
        val fimDoToque = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (toqueEmAndamento && fimDoToque) {
            toqueEmAndamento = false
            filhoSobODedo = null
            liberarDepois()
            // Por post: o UP ainda termina dentro do Editor, e a descida não pode mexer na lista no meio desse
            // despacho.
            val acao = acaoDepoisDoToque
            acaoDepoisDoToque = null
            if (acao != null) post { acao() }
        }
        return tratado
    }

    /**
     * Porta única de "confira a seleção viva e solte as repinturas" (rb-38, rb-54). Por post: o fim do toque e o
     * fechar da seleção vêm de dentro do layout ou do próprio toque, e repintar ali daria IllegalStateException.
     */
    internal fun liberarDepois() {
        post {
            aoConferirSelecao?.invoke()
            if (!textoEmUso) aoLiberarTexto?.invoke()
        }
    }

    /**
     * Pedido de "mostrar este trecho" de um filho (rb-55). Só o parágrafo de leitura entra na regra: edição e
     * comparação sempre passam. Limites aceitos: fora do modo de toque (D-pad) o setText ainda pode puxar a lista, e
     * um ACTION_SHOW_ON_SCREEN do TalkBack sobre o parágrafo em foco é ignorado.
     */
    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean {
        val tv = (getChildViewHolder(child) as? ParagrafoAdapter.VHLeitura)?.tv
        if (tv != null && !pedidoDeMostrarTrechoVale(child.isInTouchMode, tv.isFocused, tv.hasSelection())) return false
        return super.requestChildRectangleOnScreen(child, rect, immediate)
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        (getChildViewHolder(child) as? ParagrafoAdapter.VHLeitura)?.tv?.rearmarSelecao()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val rolagem = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (rolagem) {
            aoRolagemDeAcessibilidade?.invoke()
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
