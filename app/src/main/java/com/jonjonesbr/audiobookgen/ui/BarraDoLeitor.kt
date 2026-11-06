package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Barra do Leitor sobreposta ao texto (rb-66, passo 2): o cabeçalho e, depois, o painel da guiada. Avisa quando
 * alguém toca nela ([aoTocar] no primeiro toque), e [toqueEmAndamento] fica verdadeiro enquanto o dedo está na
 * barra (até UP ou CANCEL). Só sobrescreve `dispatchTouchEvent`: nada de setter chamado por atributo XML no construtor
 * (armadilha da view customizada do CLAUDE.md). Não consome o toque: sempre devolve o `super`, então os botões seguem
 * funcionando.
 */
class BarraDoLeitor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Chamado no primeiro toque (ACTION_DOWN) numa barra do Leitor: interação que não deve recolher a barra. */
    var aoTocar: (() -> Unit)? = null

    /** O dedo está sobre a barra: do ACTION_DOWN até o ACTION_UP ou o ACTION_CANCEL. */
    var toqueEmAndamento = false
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toqueEmAndamento = true
                aoTocar?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> toqueEmAndamento = false
        }
        return super.dispatchTouchEvent(ev)
    }
}
