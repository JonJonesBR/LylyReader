package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Fator de arredondamento na conversão dp→px (metade de um pixel). */
private const val FATOR_ARREDONDAMENTO_DP = 0.5f

/**
 * Converte dp para pixels usando a densidade do dispositivo. Estava duplicada (mesma linha)
 * em MainActivity, ReaderActivity, OnboardingActivity, HelpActivity e VoiceDownloadFlow.
 */
fun Context.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + FATOR_ARREDONDAMENTO_DP).toInt()

private const val FADE_IN_DURATION_MS = 200L
private const val FADE_OUT_DURATION_MS = 180L

/** Torna a view visível com fade-in. Estava duplicada em MainActivity e ConversionWorkflowController. */
fun View.mostrarComFade(duration: Long = FADE_IN_DURATION_MS) {
    if (visibility == View.VISIBLE) return
    alpha = 0f
    visibility = View.VISIBLE
    animate().alpha(1f).setDuration(duration).start()
}

/** Esconde a view com fade-out. Estava duplicada em MainActivity e ConversionWorkflowController. */
fun View.esconderComFade(duration: Long = FADE_OUT_DURATION_MS) {
    if (visibility == View.GONE) return
    animate()
        .alpha(0f)
        .setDuration(duration)
        .withEndAction {
            visibility = View.GONE
            alpha = 1f
        }
        .start()
}

/** Limita e centraliza conteúdo em telas largas, acompanhando redimensionamentos da janela. */
fun View.limitarLarguraEmTelaAmpla(larguraMaximaDp: Int) {
    val container = parent as? ViewGroup ?: return
    val larguraMaximaPx = context.dpToPx(larguraMaximaDp)

    container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val larguraContainer = container.width
        if (larguraContainer <= 0) return@addOnLayoutChangeListener

        val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return@addOnLayoutChangeListener
        val larguraDisponivel = larguraContainer - container.paddingLeft - container.paddingRight -
            params.leftMargin - params.rightMargin
        val larguraAlvo = minOf(maxOf(0, larguraDisponivel), larguraMaximaPx)

        when (params) {
            is LinearLayout.LayoutParams -> {
                if (params.width == larguraAlvo && params.gravity == Gravity.CENTER_HORIZONTAL) {
                    return@addOnLayoutChangeListener
                }
                params.width = larguraAlvo
                params.gravity = Gravity.CENTER_HORIZONTAL
            }
            is FrameLayout.LayoutParams -> {
                if (params.width == larguraAlvo && params.gravity == Gravity.CENTER_HORIZONTAL) {
                    return@addOnLayoutChangeListener
                }
                params.width = larguraAlvo
                params.gravity = Gravity.CENTER_HORIZONTAL
            }
            else -> return@addOnLayoutChangeListener
        }
        layoutParams = params
    }
}
