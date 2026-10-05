package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.dpToPx

/**
 * Indicador de páginas (bolinhas) das telas de introdução paginadas — estava duplicado
 * (criarDots/atualizarDots, quase idênticas) entre OnboardingActivity e HelpActivity.
 */
class PageDotsIndicator(
    private val context: Context,
    private val container: LinearLayout
) {
    private val dots = mutableListOf<View>()

    fun criar(quantidade: Int) {
        container.removeAllViews()
        dots.clear()
        repeat(quantidade) { i ->
            val dot = View(context).apply {
                val dp10 = context.dpToPx(DOT_ATIVO_DP)
                layoutParams = LinearLayout.LayoutParams(dp10, dp10).apply {
                    setMargins(context.dpToPx(MARGEM_DOTS_DP), 0, context.dpToPx(MARGEM_DOTS_DP), 0)
                }
                background = ContextCompat.getDrawable(
                    context,
                    if (i == 0) R.drawable.dot_active else R.drawable.dot_inactive
                )
            }
            dots.add(dot)
            container.addView(dot)
        }
    }

    fun atualizar(posicao: Int) {
        dots.forEachIndexed { i, dot ->
            val ativo = i == posicao
            val tamanho = context.dpToPx(if (ativo) DOT_ATIVO_DP else DOT_INATIVO_DP)
            (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = tamanho
                height = tamanho
            }
            dot.background = ContextCompat.getDrawable(
                context,
                if (ativo) R.drawable.dot_active else R.drawable.dot_inactive
            )
            dot.requestLayout()
        }
    }

    companion object {
        private const val DOT_ATIVO_DP = 10
        private const val DOT_INATIVO_DP = 8
        private const val MARGEM_DOTS_DP = 5
    }
}
