package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de parágrafos do leitor. Existe só para saber quando o TalkBack pede uma rolagem (ações de acessibilidade
 * SCROLL_FORWARD/BACKWARD): o RecyclerView não avisa isso, e a leitura guiada precisa tratar essa rolagem como
 * manual, igual ao arraste com o dedo. A rolagem automática do código não passa por aqui.
 */
class RecyclerViewParagrafos @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    /** Chamado antes de a rolagem pedida pela acessibilidade acontecer. */
    var aoRolagemDeAcessibilidade: (() -> Unit)? = null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val rolagem = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (rolagem) {
            aoRolagemDeAcessibilidade?.invoke()
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
