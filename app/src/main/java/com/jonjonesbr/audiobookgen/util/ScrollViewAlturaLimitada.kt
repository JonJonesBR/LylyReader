package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import kotlin.math.min
import kotlin.math.roundToInt

/** Fração da altura da tela que a rolagem pode ocupar: sobra espaço para o título e os botões do diálogo. */
private const val FRACAO_ALTURA_DA_TELA = 0.6

/**
 * Altura máxima da rolagem, em px: [FRACAO_ALTURA_DA_TELA] da altura da tela. Pura, para o teste.
 */
fun alturaMaximaDaRolagem(alturaDaTelaPx: Int): Int = (alturaDaTelaPx * FRACAO_ALTURA_DA_TELA).roundToInt()

/**
 * ScrollView que cresce com o conteúdo (layout_height="wrap_content") mas não passa de 60% da altura da tela:
 * com pouco conteúdo a janela fica pequena; com muito, a lista rola. O ScrollView comum não tem altura máxima.
 */
class ScrollViewAlturaLimitada @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    /**
     * Altura da janela do app, em px. Em multi-janela, [screenHeightDp] acompanha a janela e o display não.
     * Configuration.SCREEN_HEIGHT_DP_UNDEFINED (0) cai na altura do display: 0 px colapsaria a rolagem.
     */
    private fun alturaDaJanelaPx(): Int {
        val dp = resources.configuration.screenHeightDp
        if (dp <= 0) return resources.displayMetrics.heightPixels
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alturaMaximaPx = alturaMaximaDaRolagem(alturaDaJanelaPx())
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
}
