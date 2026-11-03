package com.jonjonesbr.audiobookgen.ui

import android.content.Context
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

    /** Há dedo na lista, do ACTION_DOWN até o UP ou o CANCEL. */
    var toqueEmAndamento = false
        private set

    /** Quantos menus de seleção de texto estão abertos agora (o adapter conta). */
    var selecoesAbertas = 0
        private set

    /**
     * Verdadeiro enquanto há toque, menu de seleção ou texto selecionado com foco: as repinturas de sincronia esperam.
     * O texto selecionado cobre o intervalo entre o UP do toque longo e a abertura do menu (o Editor a abre depois).
     */
    val textoEmUso: Boolean
        get() = segurarRepinturaDeSincronia(toqueEmAndamento, selecoesAbertas, textoComSelecaoFocada())

    /** Seleção de texto agora: menu aberto, ou texto já marcado com foco (antes de o menu abrir). Usada no toque. */
    val selecaoDeTextoAberta: Boolean
        get() = selecoesAbertas > 0 || textoComSelecaoFocada()

    private fun textoComSelecaoFocada(): Boolean = (findFocus() as? TextView)?.hasSelection() == true

    fun abriuSelecao() {
        selecoesAbertas++
    }

    fun fechouSelecao() {
        selecoesAbertas = (selecoesAbertas - 1).coerceAtLeast(0)
        liberarDepois()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) toqueEmAndamento = true
        val tratado = super.dispatchTouchEvent(ev)
        val fimDoToque = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (toqueEmAndamento && fimDoToque) {
            toqueEmAndamento = false
            liberarDepois()
        }
        return tratado
    }

    // Por post: o fim do toque e o fechar da seleção vêm de dentro do layout ou do próprio toque, e repintar
    // ali daria IllegalStateException.
    private fun liberarDepois() {
        post { if (!textoEmUso) aoLiberarTexto?.invoke() }
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
