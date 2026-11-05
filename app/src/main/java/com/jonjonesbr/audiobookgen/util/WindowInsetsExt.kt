package com.jonjonesbr.audiobookgen.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import kotlin.math.roundToInt

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

/**
 * Quanto a view ainda desce abaixo do começo da barra de navegação, em px. Quem já descontou a barra (o pai com
 * fitsSystemWindows põe os filhos acima dela) fica com 0; quem não descontou fica com a altura da barra; no meio, a
 * parte que entra nela. Função pura, para o teste sem Android.
 *
 * @param fundoNaJanela fundo da view na janela (a base da ancoragem, sem a margem e a translação da própria view).
 * @param alturaDaJanela altura da janela inteira, com a área da barra de navegação.
 * @param navInferior altura da barra de navegação embaixo da janela.
 */
internal fun sobreposicaoComANavegacao(fundoNaJanela: Int, alturaDaJanela: Int, navInferior: Int): Int =
    (fundoNaJanela - (alturaDaJanela - navInferior)).coerceAtLeast(0)

/**
 * Versão de [aplicarPaddingInferiorComNavigationBar] para a view que o pai já põe acima da barra de navegação
 * (fitsSystemWindows). Põe no padding inferior só a sobreposição real: base + quanto a view ainda desce abaixo da
 * barra. Como a view é ancorada embaixo, mudar o padding não move a base, então não há laço. Não consome insets:
 * só guarda a altura da barra de navegação para os outros ouvintes.
 */
fun View.aplicarPaddingInferiorSoNaSobreposicao() {
    val paddingBaseBottom = paddingBottom
    var navInferior = 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (nav != navInferior) {
            navInferior = nav
            view.requestLayout()
        }
        insets
    }
    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val alvo = paddingBaseBottom + view.sobreposicaoDaView(navInferior)
        // Fora do layout: pedir layout dentro dele avisa "requestLayout during layout". Só agenda quando muda.
        if (view.paddingBottom != alvo) view.post { view.updatePadding(bottom = alvo) }
    }
}

/**
 * Mesma ideia de [aplicarPaddingInferiorSoNaSobreposicao], para o FAB (margem em vez de padding).
 * A margem muda a posição da view, então a sobreposição é medida pela base da ancoragem
 * (soma a margem atual de volta), e não pelo fundo visível: assim o valor não oscila a cada layout.
 */
fun View.aplicarMargemInferiorSoNaSobreposicao() {
    val margemBase = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    var navInferior = 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (nav != navInferior) {
            navInferior = nav
            view.requestLayout()
        }
        insets
    }
    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val alvo = margemBase + view.sobreposicaoDaView(navInferior)
        val atual = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
        if (atual != alvo) {
            view.post { view.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = alvo } }
        }
    }
}

/** Sobreposição desta view com a barra de navegação, pela posição real dela na janela. */
private fun View.sobreposicaoDaView(navInferior: Int): Int =
    sobreposicaoComANavegacao(baseDaAncoragemNaJanela(), rootView.height, navInferior)

/**
 * Onde a view estaria sem a margem de baixo e sem a translação que ela mesma põe (o mini player sobe o FAB por
 * translationY). É a base que o pai lhe dá, então não muda quando a própria margem muda.
 */
private fun View.baseDaAncoragemNaJanela(): Int {
    val posicao = IntArray(2)
    getLocationInWindow(posicao)
    val margemDeBaixo = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    return posicao[1] + height + margemDeBaixo - translationY.roundToInt()
}
