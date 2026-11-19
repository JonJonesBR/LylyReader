package com.jonjonesbr.audiobookgen.util

import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Em paisagem a altura é curta (~360dp): uma folha que abre recolhida corta o conteúdo e
 * deixa o restante inacessível. Por isso, nessa orientação, a folha abre já expandida.
 * Em retrato o comportamento não muda.
 */
fun precisaExpandirFolha(orientacao: Int): Boolean = orientacao == Configuration.ORIENTATION_LANDSCAPE

/** Aplica [precisaExpandirFolha] ao [BottomSheetDialog]. Chamar depois de [BottomSheetDialog.setContentView]. */
fun BottomSheetDialog.expandirEmPaisagem() {
    if (!precisaExpandirFolha(context.resources.configuration.orientation)) return
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
    behavior.skipCollapsed = true
}

/**
 * Abre a folha já expandida, em qualquer orientação. Para folhas com lista rolável: no estado
 * recolhido, rolar a lista para baixo com força fechava a folha. Expandida, a lista rola e a
 * folha só fecha ao arrastar com a lista no topo, pela alça ou com Voltar.
 * Chamar depois de [BottomSheetDialog.setContentView].
 */
fun BottomSheetDialog.abrirExpandida() {
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
    behavior.skipCollapsed = true
}

/**
 * Quanto a folha ainda desce abaixo do começo da barra de navegação, em px, limitado à altura da
 * própria barra. Folha ancorada embaixo: o fundo dela é o da janela, então a folga dá a altura da barra.
 * Se o Material já a levantou acima da barra, dá 0. Folha recolhida (fundo fora da tela): dá a barra
 * inteira, nunca mais. Função pura, para o teste.
 *
 * @param fundoNaJanela fundo da folha na janela.
 * @param alturaDaJanela altura da janela inteira, com a área da barra de navegação.
 * @param navInferior altura da barra de navegação embaixo da janela; negativa vira 0.
 */
internal fun folgaParaBarraDeNavegacao(fundoNaJanela: Int, alturaDaJanela: Int, navInferior: Int): Int {
    val barra = navInferior.coerceAtLeast(0)
    return sobreposicaoComANavegacao(fundoNaJanela, alturaDaJanela, barra).coerceIn(0, barra)
}

/**
 * Mantém o fim do conteúdo de uma folha acima da barra de navegação de 3 botões. Em edge-to-edge (targetSdk 36) o
 * Material pode não repassar a inset da barra a esta folha, e o último item fica atrás dela. O padding inferior
 * recebe base + [folgaParaBarraDeNavegacao]. A altura da barra é lida na própria janela a cada layout, sem depender
 * de `setOnApplyWindowInsetsListener`, que a folha pode não receber.
 *
 * Não há laço: a folha é ancorada embaixo, então mudar o padding do conteúdo não move o fundo dela
 * (só a altura).
 * O padding é trocado por `post`, fora do callback de layout, para não pedir layout dentro do layout.
 * Chamar depois de [BottomSheetDialog.setContentView].
 */
fun BottomSheetDialog.manterAcimaDaBarraDeNavegacao(conteudo: View) {
    val paddingBaseBottom = conteudo.paddingBottom
    conteudo.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val navInferior = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val posicao = IntArray(2)
        view.getLocationInWindow(posicao)
        val fundoNaJanela = posicao[1] + view.height
        val alvo = paddingBaseBottom + folgaParaBarraDeNavegacao(fundoNaJanela, view.rootView.height, navInferior)
        if (view.paddingBottom != alvo) view.post { view.updatePadding(bottom = alvo) }
    }
    // Um layout a mais depois que a folha entra na janela: se a barra só for conhecida depois do 1º layout,
    // o ouvinte acima roda de novo e acerta o padding. A ação adiada espera o anexo à janela, se ainda não houve.
    conteudo.post { conteudo.requestLayout() }
}
