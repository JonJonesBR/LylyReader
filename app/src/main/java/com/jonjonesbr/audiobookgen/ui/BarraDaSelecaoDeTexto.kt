package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.HorizontalScrollView
import com.jonjonesbr.audiobookgen.R

/**
 * Barra própria do Leitor com as ações da seleção nativa de texto (rb-38). Existe porque o menu da seleção é do
 * sistema: alguns fabricantes (ex.: Xiaomi/HyperOS) mandam os itens do app para o ⋮ ou nem os mostram. A barra
 * fica sobreposta no topo da lista e não empurra o texto. Quem decide quais botões aparecem é
 * [acoesDaBarraDeSelecao]; aqui só se liga cada botão à sua ação.
 */
internal class BarraDaSelecaoDeTexto(
    private val barra: View,
    comAudio: Boolean,
    private val aoTocar: (AcaoDaBarraDeSelecao) -> Unit,
) {
    init {
        val visiveis = acoesDaBarraDeSelecao(comAudio)
        BOTOES.forEach { (acao, idDoBotao) ->
            val botao = barra.findViewById<View>(idDoBotao)
            botao.visibility = if (acao in visiveis) View.VISIBLE else View.GONE
            botao.setOnClickListener { aoTocar(acao) }
        }
    }

    /** Mostra a barra e volta ao começo (ela rola na horizontal quando não cabe na tela). */
    fun mostrar() {
        barra.visibility = View.VISIBLE
        (barra as? HorizontalScrollView)?.scrollTo(0, 0)
    }

    fun esconder() {
        barra.visibility = View.GONE
    }

    private companion object {
        val BOTOES: List<Pair<AcaoDaBarraDeSelecao, Int>> = listOf(
            AcaoDaBarraDeSelecao.GERAR_AUDIO to R.id.btnSelecaoTextoGerar,
            AcaoDaBarraDeSelecao.DESTACAR to R.id.btnSelecaoTextoDestacar,
            AcaoDaBarraDeSelecao.PRONUNCIA to R.id.btnSelecaoTextoPronuncia,
            AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS to R.id.btnSelecaoTextoVarios,
            AcaoDaBarraDeSelecao.DEFINIR to R.id.btnSelecaoTextoDefinir,
        )
    }
}
