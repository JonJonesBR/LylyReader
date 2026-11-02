package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import kotlin.math.min

/**
 * ScrollView que cresce com o conteúdo (layout_height="wrap_content") mas não passa de [ALTURA_MAXIMA_DP]:
 * com pouco conteúdo a janela fica pequena; com muito, a lista rola. O ScrollView comum não tem altura máxima.
 */
class ScrollViewAlturaLimitada @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val alturaMaximaPx = (ALTURA_MAXIMA_DP * context.resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alturaSpec = when (View.MeasureSpec.getMode(heightMeasureSpec)) {
            // Altura fixada pelo pai: não há o que limitar
            View.MeasureSpec.EXACTLY -> heightMeasureSpec
            View.MeasureSpec.AT_MOST -> View.MeasureSpec.makeMeasureSpec(
                min(View.MeasureSpec.getSize(heightMeasureSpec), alturaMaximaPx),
                View.MeasureSpec.AT_MOST
            )
            else -> View.MeasureSpec.makeMeasureSpec(alturaMaximaPx, View.MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, alturaSpec)
    }

    private companion object {
        private const val ALTURA_MAXIMA_DP = 320
    }
}
