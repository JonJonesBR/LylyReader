package com.jonjonesbr.audiobookgen.ui

import android.view.View
import androidx.core.view.doOnLayout
import com.jonjonesbr.audiobookgen.R
import kotlin.math.roundToInt

/**
 * Barra própria do Leitor com as ações da seleção nativa de texto (rb-38). Existe porque o menu da seleção é do
 * sistema: alguns fabricantes (ex.: Xiaomi/HyperOS) mandam os itens do app para o ⋮ ou nem os mostram. A barra
 * fica sobreposta no topo da lista e não empurra o texto. Cabe na largura da tela, sem rolar (rb-43). Quem decide
 * quais botões aparecem é [acoesDaBarraDeSelecao]; aqui só se liga cada botão à sua ação.
 *
 * Quando a seleção começa sob a barra, a lista desce por translação só o necessário, com uma margem que também
 * abre espaço para o menu flutuante do sistema (fica acima da seleção). Volta ao lugar quando a barra some (rb-48).
 * A descida não rola a lista: não mexe na posição de leitura nem nos listeners.
 */
internal class BarraDaSelecaoDeTexto(
    private val barra: View,
    private val lista: RecyclerViewParagrafos,
    comAudio: Boolean,
    private val aoTocar: (AcaoDaBarraDeSelecao) -> Unit,
) {
    // Verdadeiro só entre o mostrar que abriu a barra e a primeira descida dela: a sessão nova pode voltar ao lugar.
    private var sessaoNova = false

    /** Se a barra está na tela agora (rb-54: a barra sem o modo do sistema confere isto para o Voltar). */
    val visivel: Boolean get() = barra.visibility == View.VISIBLE

    init {
        val visiveis = acoesDaBarraDeSelecao(comAudio)
        BOTOES.forEach { (acao, idDoBotao) ->
            val botao = barra.findViewById<View>(idDoBotao)
            botao.visibility = if (acao in visiveis) View.VISIBLE else View.GONE
            botao.setOnClickListener { aoTocar(acao) }
        }
    }

    /**
     * Mostra a barra e esconde a pílula da limpeza (rb-45). O layout já põe a pílula numa coluna acima da lista,
     * então a barra não a cobre; esconder é garantia extra. Se a limpeza terminar com a barra aberta, o controller
     * ainda pode mostrar a pílula (ela não é travada aqui): o caso é raro e não há sobreposição.
     *
     * [linhaDaSelecao] diz onde está a linha do início da seleção na tela. A descida espera o toque terminar
     * (rb-48) e a barra ser medida: a altura da barra só existe depois do layout.
     *
     * [outraSelecao] diz que a seleção mudou de parágrafo (outro menu): mesmo com a barra já visível, é sessão nova,
     * e a descida pode voltar ao lugar.
     */
    fun mostrar(outraSelecao: Boolean = false, linhaDaSelecao: () -> LinhaNaTela?) {
        // A pílula fica na coluna do topo, fora da área da lista. Achá-la pela raiz evita passar mais uma view
        // ao ParagrafoAdapter.
        barra.rootView.findViewById<View>(R.id.bannerTextoOtimizado)?.visibility = View.GONE
        // Antes de mostrar: depois disso a barra já está visível e não dá para saber se é sessão nova (rb-48).
        sessaoNova = outraSelecao || barra.visibility != View.VISIBLE
        barra.visibility = View.VISIBLE
        lista.depoisDoToque { barra.doOnLayout { descerSePreciso(linhaDaSelecao) } }
    }

    /** Esconde a barra e devolve a lista ao lugar, com a volta animada (rb-48). */
    fun esconder() {
        barra.visibility = View.GONE
        if (lista.translationY != 0f) {
            lista.animate().translationY(0f).setDuration(DURACAO_VOLTA_MS).start()
        }
    }

    // Quem chama já garante que a barra está medida. Só mexe na translação: nada de rolagem nem de layout.
    private fun descerSePreciso(linhaDaSelecao: () -> LinhaNaTela?) {
        // Sai se a seleção fechou antes deste momento (o post de fechar pode ter chegado primeiro). Com a barra sem o
        // modo do sistema (rb-54), a seleção viva também conta: a descida vale para ela. Só a seleção do Leitor
        // conta aqui (regra restrita, não a larga): a barra não desce por causa de seleção da edição.
        if (barra.visibility != View.VISIBLE) return
        if (lista.selecoesAbertas == 0 && lista.textoComSelecaoViva == null) return
        val linha = linhaDaSelecao() ?: return
        // Uma volta animada em curso (barra reaberta logo depois de fechar) cede ao cálculo a partir do ponto atual.
        lista.animate().cancel()
        val atual = lista.translationY.roundToInt()
        val posBarra = IntArray(2)
        barra.getLocationOnScreen(posBarra)
        // A base da barra em coordenadas da tela, as mesmas da linha. O getGlobalVisibleRect é da janela e não serve.
        val fundoDaBarra = posBarra[1] + barra.height
        // O menu flutuante da seleção fica acima dela, por isso o espaço dele entra na margem (rb-48).
        val espacoEmDp = ESPACO_DO_MENU_FLUTUANTE_DP + MARGEM_ABAIXO_DA_BARRA_DP
        val margemPx = (espacoEmDp * barra.resources.displayMetrics.density).roundToInt()
        val necessario = quantoDescerParaMostrarSelecao(linha, fundoDaBarra, barra.height, margemPx, atual)
        // Dentro da mesma seleção a lista não sobe, para não pular ao trocar de seleção. Numa sessão nova,
        // o necessário vale, e a lista pode voltar ao lugar se a seleção não precisar de descida.
        val alvo = if (sessaoNova) necessario else maxOf(atual, necessario)
        sessaoNova = false
        when {
            // Descer é direto, sem animação: o estado fica estável para medir.
            alvo > atual -> lista.translationY = alvo.toFloat()
            // Subir só acontece numa sessão nova: a lista volta com a mesma animação da barra que some.
            alvo < atual -> lista.animate().translationY(alvo.toFloat()).setDuration(DURACAO_VOLTA_MS).start()
        }
    }

    private companion object {
        val BOTOES: List<Pair<AcaoDaBarraDeSelecao, Int>> = listOf(
            AcaoDaBarraDeSelecao.GERAR_AUDIO to R.id.btnSelecaoTextoGerar,
            AcaoDaBarraDeSelecao.DESTACAR to R.id.btnSelecaoTextoDestacar,
            AcaoDaBarraDeSelecao.PRONUNCIA to R.id.btnSelecaoTextoPronuncia,
            AcaoDaBarraDeSelecao.DEFINIR to R.id.btnSelecaoTextoDefinir,
            AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS to R.id.btnSelecaoTextoVarios,
        )

        // Espaço que o menu flutuante da seleção ocupa acima dela: 48 dp do menu e 8 dp de margem em cima e
        // embaixo. O menu do sistema fica acima da seleção, então a lista desce para ele caber entre a barra e a
        // linha (rb-48).
        const val ESPACO_DO_MENU_FLUTUANTE_DP = 64f

        // Respiro entre a base da barra e a linha da seleção, em dp (convertido para px ao usar).
        const val MARGEM_ABAIXO_DA_BARRA_DP = 8f
        const val DURACAO_VOLTA_MS = 150L
    }
}
