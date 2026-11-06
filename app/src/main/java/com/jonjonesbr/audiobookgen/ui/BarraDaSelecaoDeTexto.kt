package com.jonjonesbr.audiobookgen.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.jonjonesbr.audiobookgen.R
import kotlin.math.roundToInt

/**
 * Barra própria do Leitor com as ações da seleção nativa de texto (rb-38). Existe porque o menu da seleção é do
 * sistema: alguns fabricantes (ex.: Xiaomi/HyperOS) mandam os itens do app para o ⋮ ou nem os mostram. A barra
 * fica sobreposta no topo da lista (no modo páginas, embaixo: ver rb-26b abaixo) e não empurra o texto. Cabe na
 * largura da tela, sem rolar (rb-43). Quem decide quais botões aparecem é [acoesDaBarraDeSelecao]; aqui só se liga
 * cada botão à sua ação.
 *
 * Quando a seleção começa sob a barra, a lista desce por translação só o necessário, com uma margem que também
 * abre espaço para o menu flutuante do sistema (fica acima da seleção). Volta ao lugar quando a barra some (rb-48).
 * A descida não rola a lista: não mexe na posição de leitura nem nos listeners.
 *
 * O alvo da descida fica em `lista.descidaDaSelecao`, e o espaço depois do último parágrafo (rb-51, ver
 * [EspacoDaDescidaNoFim]) soma esse alvo enquanto a barra está aberta. Quem muda o alvo avisa `lista.aoMudarDescida`,
 * para esse espaço ser refeito.
 *
 * rb-26b (passo 3): no modo páginas a barra fica embaixo, no lugar do painel da guiada, e o texto não desce (a
 * descida vira zero). O painel é cedido por INVISIBLE (a página não muda de tamanho) e a marca
 * [RecyclerViewParagrafos.baseCedidaASelecao] segue o lugar aplicado. O lugar é decidido no `mostrar` ([embaixo]); o
 * resto do código lê [lugar], não [embaixo] de novo. No vertical nada muda: a barra fica no topo, com a descida.
 */
internal class BarraDaSelecaoDeTexto(
    private val barra: View,
    private val lista: RecyclerViewParagrafos,
    comAudio: Boolean,
    private val embaixo: () -> Boolean = { lista.paginacao?.ativa == true },
    private val aoTocar: (AcaoDaBarraDeSelecao) -> Unit,
) {
    // Verdadeiro só entre o mostrar que abriu a barra e a primeira descida dela: a sessão nova pode voltar ao lugar.
    private var sessaoNova = false

    // Lugar aplicado de fato na barra (rb-26b, passo 3). Todas as decisões leem daqui.
    private var lugar = LugarDaBarraDaSelecao(embaixo = false, margemDeBaixo = 0, desce = true)

    // Recálculo pedido ao soltar a alça da seleção (rb-50): a linha e o que roda depois da descida, guardados até o
    // post.
    private var linhaDoReajuste: (() -> LinhaNaTela?)? = null
    private var depoisDoReajuste: (() -> Unit)? = null
    private val reajuste = Runnable { linhaDoReajuste?.let { agendarDescida(it, depoisDoReajuste ?: {}) } }

    /** Se a barra está na tela agora (rb-54: a barra sem o modo do sistema confere isto para o Voltar). */
    val visivel: Boolean get() = barra.visibility == View.VISIBLE

    /**
     * Limite de cima da área útil, em coordenadas da tela (rb-60b): a base da barra mais a mesma margem da descida
     * (rb-48). Um trecho que começa acima dele fica sob a barra. Nulo com a barra escondida.
     */
    val limiteDaSelecaoNaTela: Int?
        get() {
            if (!visivel || lugar.embaixo) return null
            val posBarra = IntArray(2)
            barra.getLocationOnScreen(posBarra)
            return posBarra[1] + barra.height + margemPx
        }

    /**
     * Topo da barra na tela quando ela está embaixo (rb-26b, passo 3): o limite de baixo da área em que o trecho
     * guardado pode voltar sozinho (rb-60b). Nulo com a barra escondida ou no topo.
     */
    val topoNaTelaEmbaixo: Int?
        get() {
            if (!visivel || !lugar.embaixo) return null
            val posBarra = IntArray(2)
            barra.getLocationOnScreen(posBarra)
            return posBarra[1]
        }

    // Espaço entre a base da barra e a linha da seleção: o do menu flutuante mais o respiro (rb-48). Usada pela
    // descida e pelo limite da reabertura (rb-60b), para as duas terem a mesma margem.
    private val margemPx: Int
        get() = ((ESPACO_DO_MENU_FLUTUANTE_DP + MARGEM_ABAIXO_DA_BARRA_DP) * barra.resources.displayMetrics.density)
            .roundToInt()

    init {
        val visiveis = acoesDaBarraDeSelecao(comAudio)
        BOTOES.forEach { (acao, idDoBotao) ->
            val botao = barra.findViewById<View>(idDoBotao)
            botao.visibility = if (acao in visiveis) View.VISIBLE else View.GONE
            botao.setOnClickListener { aoTocar(acao) }
        }
        // Mini player que aparece, some ou abre com a barra embaixo: a barra sobe ou desce pela margem
        // (rb-26b, passo 3). Só pede layout se mudou, então converge.
        lista.viewTreeObserver.addOnGlobalLayoutListener { if (visivel && lugar.embaixo) posicionar(embaixo = true) }
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
     *
     * [depois] roda depois de cada descida que o mostrar agenda (o menu grava ali a seleção descida, rb-50). Fica antes
     * de [linhaDaSelecao] para o trailing lambda dos chamadores continuar nesse parâmetro.
     */
    fun mostrar(outraSelecao: Boolean = false, depois: () -> Unit = {}, linhaDaSelecao: () -> LinhaNaTela?) {
        // A pílula fica na coluna do topo, fora da área da lista. Achá-la pela raiz evita passar mais uma view
        // ao ParagrafoAdapter.
        barra.rootView.findViewById<View>(R.id.bannerTextoOtimizado)?.visibility = View.GONE
        // Antes de mostrar: depois disso a barra já está visível e não dá para saber se é sessão nova (rb-48).
        sessaoNova = outraSelecao || barra.visibility != View.VISIBLE
        posicionar(embaixo())
        cederPainel(lugar.embaixo)
        barra.visibility = View.VISIBLE
        agendarDescida(linhaDaSelecao, depois)
    }

    /**
     * Refaz a descida depois de soltar a alça da seleção (rb-50): com a seleção nova, a linha do início pode ter ido
     * para sob a barra. O preparo do menu que vem ao soltar roda dentro do toque da alça, por isso o cálculo é postado.
     * [depois] roda junto com a descida (o menu pede a barra flutuante de novo, para a barra do sistema não cobrir a
     * seleção no Xiaomi). Duas soltadas seguidas se juntam: o post anterior é retirado.
     */
    fun reajustar(linhaDaSelecao: () -> LinhaNaTela?, depois: () -> Unit) {
        if (!visivel) return
        linhaDoReajuste = linhaDaSelecao
        depoisDoReajuste = depois
        lista.removeCallbacks(reajuste)
        lista.post(reajuste)
    }

    // Mesmo caminho do mostrar (rb-48): espera o toque terminar e a barra ser medida, e só então desce o necessário.
    // Embaixo (rb-26b, passo 3), o texto não desce: o lugar da barra não pede a descida.
    private fun agendarDescida(linhaDaSelecao: () -> LinhaNaTela?, depois: () -> Unit = {}) {
        lista.depoisDoToque {
            barra.doOnLayout {
                if (lugar.desce) descerSePreciso(linhaDaSelecao) else ficarNoLugar()
                depois()
            }
        }
    }

    // Embaixo: a lista fica como está (translação zero, sem animação) e a descida guardada vai a zero
    // (rb-26b, passo 3).
    private fun ficarNoLugar() {
        sessaoNova = false
        lista.animate().cancel()
        if (lista.translationY != 0f) lista.translationY = 0f
        guardarDescida(0)
    }

    // Põe a barra no topo ou embaixo da área da lista, pela gravidade e pela margem do mini player (rb-26b, passo 3).
    // Só pede layout se a gravidade ou a margem mudaram.
    private fun posicionar(embaixo: Boolean) {
        val params = barra.layoutParams as? FrameLayout.LayoutParams ?: return
        val area = barra.parent as? View
        val miniPlayer = barra.rootView.findViewById<View>(R.id.miniPlayerRoot)
            ?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
        val novo = lugarDaBarraDaSelecao(
            paginasAtivas = embaixo,
            fundoDaArea = area?.let { it.topoNaJanela() + it.height } ?: 0,
            topoDoMiniPlayer = miniPlayer?.topoNaJanela(),
        )
        lugar = novo
        val gravidade = if (novo.embaixo) Gravity.BOTTOM else Gravity.TOP
        if (params.gravity != gravidade || params.bottomMargin != novo.margemDeBaixo) {
            params.gravity = gravidade
            params.bottomMargin = novo.margemDeBaixo
            barra.layoutParams = params
        }
    }

    // Cede o lugar do painel da guiada à barra embaixo (INVISIBLE: continua medido, e a página não muda de tamanho).
    // Sem ceder, devolve o painel só se foi a barra que o escondeu (a guiada repõe o VISIBLE pela sua marca).
    // A marca segue o lugar, não a visibilidade do painel: com o painel GONE (guiada parada) ela fica igual.
    private fun cederPainel(ceder: Boolean) {
        val painel = barra.rootView.findViewById<View>(R.id.layoutGuidedPlayer)
        if (painel != null) {
            if (ceder && painel.visibility == View.VISIBLE) painel.visibility = View.INVISIBLE
            if (!ceder && lista.baseCedidaASelecao && painel.visibility == View.INVISIBLE) {
                painel.visibility = View.VISIBLE
            }
        }
        lista.baseCedidaASelecao = ceder
    }

    /**
     * Esconde a barra e devolve a lista ao lugar, com a volta animada (rb-48). Chamado só por
     * SelecaoSemMenu.esconderBarra, dentro do post da conferência e fora do layout, então pode mexer no espaço do fim
     * já aqui (rb-51).
     */
    fun esconder() {
        // rb-51: ao tirar a descida do fim, o fixLayoutEndGap empurra o texto para baixo na mesma medida. A parte da
        // translação que cabe nesse empurrão sai sem animação, no mesmo quadro; o resto volta animado. Sem o último
        // parágrafo na tela, nada sai junto e a volta é a de sempre.
        val descida = lista.descidaNoFim()
        val junto = quantoSaiJuntoNaVolta(descida, lista.restanteAteOFim(), lista.computeVerticalScrollOffset())
        // Só refaz o espaço do fim se havia descida: sem ela, a decoração já está como deve ficar (rb-51).
        val tinhaDescida = lista.descidaDaSelecao != 0
        barra.visibility = View.GONE
        // rb-26b (passo 3): a barra sai, e o painel da guiada volta se foi a barra que o cedeu. A gravidade fica como
        // está: o próximo mostrar a aplica de novo.
        cederPainel(false)
        lista.removeCallbacks(reajuste)
        linhaDoReajuste = null
        lista.descidaDaSelecao = 0
        if (junto > 0) {
            lista.animate().cancel()
            lista.translationY = (lista.translationY - junto).coerceAtLeast(0f)
        }
        if (lista.translationY != 0f) {
            lista.animate().translationY(0f).setDuration(DURACAO_VOLTA_MS).start()
        }
        if (tinhaDescida) lista.aoMudarDescida?.invoke()
    }

    // Guarda o alvo da descida e avisa o espaço do fim (rb-51). Por post, porque a descida pode rodar dentro do
    // doOnLayout, e a invalidação das decorações pede outro layout. Subindo (rb-67/7), a volta da translação
    // roda no mesmo post, antes do aviso: o que sai junto com o empurrão do fim não anima (ver subirComOFim).
    private fun guardarDescida(alvo: Int, subindo: Boolean = false) {
        val antes = lista.descidaDaSelecao
        if (alvo == antes && !subindo) return
        lista.descidaDaSelecao = alvo
        lista.post {
            // Se a barra fechou antes deste post, o esconder já devolveu a lista e o espaço do fim (rb-67/7).
            if (subindo && lista.descidaDaSelecao == alvo) lista.subirComOFim(antes, alvo, DURACAO_VOLTA_MS)
            lista.aoMudarDescida?.invoke()
        }
    }

    // Quem chama já garante que a barra está medida. Só mexe na translação: nada de rolagem nem de layout.
    // Embaixo a descida não entra aqui (ver agendarDescida): o ReturnCount desta função está no baseline do detekt.
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
        val necessario = quantoDescerParaMostrarSelecao(linha, fundoDaBarra, barra.height, margemPx, atual)
        // Dentro da mesma seleção a lista não sobe, para não pular ao trocar de seleção. Numa sessão nova,
        // o necessário vale, e a lista pode voltar ao lugar se a seleção não precisar de descida.
        val alvo = if (sessaoNova) necessario else maxOf(atual, necessario)
        sessaoNova = false
        // Descer é direto, sem animação: o estado fica estável para medir. Subir só acontece numa sessão nova, e
        // a animação dela sai no post de guardarDescida, junto do espaço do fim (rb-67/7).
        if (alvo > atual) lista.translationY = alvo.toFloat()
        guardarDescida(alvo, subindo = alvo < atual)
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

// Topo da view na janela (rb-26b, passo 3): a área da barra e o mini player são medidos assim. Função de topo,
// para a contagem de funções da classe não crescer.
private fun View.topoNaJanela(): Int = IntArray(2).also { getLocationInWindow(it) }[1]
