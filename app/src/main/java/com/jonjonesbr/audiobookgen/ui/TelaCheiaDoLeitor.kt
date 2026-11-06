package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.RegraDasPaginasDoLeitor
import com.jonjonesbr.audiobookgen.util.dpToPx

/** Distância do deslize para mostrar ou recolher o cabeçalho: a mesma do arraste que vira a página. */
private const val LIMIAR_DO_DESLIZE_DP = 48

/**
 * Tela cheia no modo páginas do Leitor (rb-26b, passo 6): o cabeçalho (toolbar, busca e capítulo) some quando quem lê
 * vira a página com o dedo, e volta com um deslize para baixo. Não se mexe nos filhos: só no contêiner
 * `cabecalhoDoLeitor`. O painel da guiada não entra aqui.
 *
 * O cabeçalho fica por cima do conteúdo (rb-66, passo 2). Quando ele está visível, o conteúdo reserva o espaço dele com
 * o `paddingTop` (ver [atualizarReserva]), então o texto começa embaixo do cabeçalho, como antes.
 *
 * Quando o cabeçalho muda, a primeira linha da página fica no lugar (ver [manterAPrimeiraLinha]). Não se recolhe com a
 * virada da guiada, com teclas ou com o TalkBack, nem com a busca aberta. Com o leitor de tela ligado ou fora das
 * páginas, o cabeçalho sempre aparece. Sem animação: a regra é só de visibilidade.
 */
internal class TelaCheiaDoLeitor(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
    private val cabecalho: View,
    private val busca: View,
) {
    // Contêiner do texto: o paddingTop dele é a reserva do cabeçalho sobreposto.
    private val conteudo: View = activity.findViewById(R.id.conteudoDoLeitor)

    // Começa visível: a pessoa só recolhe quando vira a página com o dedo.
    private var ocultoPeloLeitor = false
    private var dicaTentada = false
    private val limiarPx = activity.dpToPx(LIMIAR_DO_DESLIZE_DP).toFloat()
    private val acessibilidade: AccessibilityManager? = activity.getSystemService(AccessibilityManager::class.java)

    /**
     * Liga os avisos da lista (gesto engolido pela página e virada pelo dedo) e a reserva: a altura do cabeçalho muda
     * com a busca e com o texto do capítulo. Chamado uma vez, no configurar.
     */
    fun ligar() {
        lista.aoGestoDaPagina = { dx, dy -> aoDeslizar(dx, dy) }
        lista.aoVirarPeloDedo = { pedir(ocultar = true) }
        // Fora do layout: mudar o padding do irmão no meio do layout do Coordinator pode ser descartado (a ordem dos
        // filhos não é a do XML). Por post, a reserva segue a altura nova no quadro seguinte.
        cabecalho.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) conteudo.post { atualizarReserva() }
        }
    }

    /**
     * Aplica a regra de visibilidade do cabeçalho. Barata: roda a cada atualização da tela, e só mexe quando o valor
     * muda. Fora das páginas (ou com busca aberta, ou com TalkBack), zera o oculto, para o cabeçalho não sumir sozinho
     * quando a busca fecha ou quando se volta ao modo páginas.
     */
    fun aplicar(paginasAtivas: Boolean) {
        val buscaAberta = busca.visibility == View.VISIBLE
        val leitorDeTela = acessibilidade?.isTouchExplorationEnabled == true
        if (!paginasAtivas || buscaAberta || leitorDeTela) ocultoPeloLeitor = false
        val visivel = RegraDasPaginasDoLeitor.cabecalhoVisivel(
            paginasAtivas, ocultoPeloLeitor, buscaAberta, leitorDeTela,
        )
        val alvo = if (visivel) View.VISIBLE else View.GONE
        if (cabecalho.visibility == alvo) return
        // Na troca de modo (Aparência, edição, comparação) o adapter refaz tudo: não se rola a lista nesse momento.
        if (paginasAtivas) {
            lista.manterAPrimeiraLinha { mudarVisibilidade(alvo) }
        } else {
            mudarVisibilidade(alvo)
        }
        if (alvo == View.GONE) tentarDica()
    }

    private fun mudarVisibilidade(alvo: Int) {
        cabecalho.visibility = alvo
        atualizarReserva()
    }

    /**
     * A reserva é o `paddingTop` do conteúdo com a altura do cabeçalho quando ele está visível, e 0 quando some. Só
     * mexe quando o valor muda. Quando o cabeçalho acaba de voltar, a altura pode ainda ser a antiga: o listener de
     * layout do cabeçalho corrige logo depois.
     */
    private fun atualizarReserva() {
        val alvo = if (cabecalho.visibility == View.VISIBLE) cabecalho.height else 0
        if (conteudo.paddingTop != alvo) conteudo.updatePadding(top = alvo)
    }

    /** Deslize sobre a página (gesto que a página engoliu). Não conta com seleção ou menu abertos. */
    private fun aoDeslizar(dx: Float, dy: Float) {
        if (lista.textoEmUso) return
        when (RegraDasPaginasDoLeitor.deslizeVertical(dx, dy, limiarPx)) {
            1 -> pedir(ocultar = false)
            -1 -> pedir(ocultar = true)
        }
    }

    private fun pedir(ocultar: Boolean) {
        ocultoPeloLeitor = ocultar
        aplicar(lista.paginacao?.ativa == true)
    }

    /** Dica da tela cheia, uma vez por abertura do Leitor. Por post, para medir a lista já com a altura nova. */
    private fun tentarDica() {
        if (dicaTentada) return
        dicaTentada = true
        lista.post {
            if (cabecalho.visibility == View.GONE) CoachMark.tentar(activity, Dica.LEITOR_TELA_CHEIA, lista)
        }
    }
}

/**
 * Muda a visibilidade do cabeçalho (e a reserva dele) mantendo a primeira linha da página. Com o cabeçalho sumindo ou
 * voltando, a lista muda de altura, e o LinearLayoutManager ancora no foco (um parágrafo focado fica no lugar) ou no
 * primeiro item, então o topo sairia do lugar. Guarda o primeiro item visível e o offset dele, roda [mudar] e pede a
 * mesma posição com [LeitorLayoutManager.fixarPrimeiraLinha], que não conta como salto. O pedido pendente tem
 * prioridade sobre a âncora do foco.
 */
internal fun RecyclerViewParagrafos.manterAPrimeiraLinha(mudar: () -> Unit) {
    val lm = layoutManager as? LeitorLayoutManager
    val pos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
    val item = lm?.findViewByPosition(pos)
    if (lm == null || item == null) {
        mudar()
        return
    }
    val ajuste = OrientationHelper.createVerticalHelper(lm)
    val offset = ajuste.getDecoratedStart(item) - ajuste.startAfterPadding
    mudar()
    lm.fixarPrimeiraLinha(pos, offset)
}
