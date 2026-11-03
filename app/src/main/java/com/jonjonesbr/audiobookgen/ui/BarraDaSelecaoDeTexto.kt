package com.jonjonesbr.audiobookgen.ui

import android.view.View
import com.jonjonesbr.audiobookgen.R

/**
 * Barra própria do Leitor com as ações da seleção nativa de texto (rb-38). Existe porque o menu da seleção é do
 * sistema: alguns fabricantes (ex.: Xiaomi/HyperOS) mandam os itens do app para o ⋮ ou nem os mostram. A barra
 * fica sobreposta no topo da lista e não empurra o texto. Cabe na largura da tela, sem rolar (rb-43). Quem decide
 * quais botões aparecem é [acoesDaBarraDeSelecao]; aqui só se liga cada botão à sua ação.
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

    /**
     * Mostra a barra e esconde a pílula da limpeza (rb-45). O layout já põe a pílula numa coluna acima da lista, então
     * a barra não a cobre; esconder é garantia extra. Se a limpeza terminar com a barra aberta, o controller ainda pode
     * mostrar a pílula (ela não é travada aqui): o caso é raro e não há sobreposição.
     */
    fun mostrar() {
        // A pílula fica na coluna do topo, fora da área da lista. Achá-la pela raiz evita passar mais uma view
        // ao ParagrafoAdapter.
        barra.rootView.findViewById<View>(R.id.bannerTextoOtimizado)?.visibility = View.GONE
        barra.visibility = View.VISIBLE
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
