package com.jonjonesbr.audiobookgen.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Soma o inset da navigation bar/gesture bar ao padding inferior JÁ EXISTENTE da view (mesmo
 * padrão já usado em `MainActivity.ajustarInsetsRodape`, generalizado — T6.1, edge-to-edge).
 * Evita que barras/conteúdo ancorados na base da tela fiquem atrás da barra de gestos.
 */
fun View.aplicarPaddingInferiorComNavigationBar() {
    val paddingBaseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.updatePadding(bottom = paddingBaseBottom + navBar.bottom)
        insets
    }
}

/** Mesma ideia, para views posicionadas por margem (ex.: FAB) em vez de padding. */
fun View.aplicarMargemInferiorComNavigationBar() {
    val margemBase = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = margemBase + navBar.bottom }
        insets
    }
}
