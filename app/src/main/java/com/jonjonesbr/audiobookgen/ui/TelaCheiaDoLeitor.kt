package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.EstadoDoCabecalho
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import com.jonjonesbr.audiobookgen.domain.RegraDasBarrasDoLeitor
import com.jonjonesbr.audiobookgen.domain.RegraDasPaginasDoLeitor
import com.jonjonesbr.audiobookgen.util.dpToPx

/** Distância do deslize para mostrar ou recolher o cabeçalho: a mesma do arraste que vira a página. */
private const val LIMIAR_DO_DESLIZE_DP = 48

/**
 * Região de cima do Leitor no modo páginas (rb-26b, passo 6; rb-66, passos 2 e 3). O cabeçalho (toolbar, busca e
 * capítulo) fica por cima do conteúdo. Quem decide se ele aparece e se ocupa lugar é o [BarrasDoLeitor], que chama
 * [aplicar] com o [EstadoDoCabecalho]. Esta classe só mostra e reserva, e manda os gestos da página ao coordenador
 * (deslize, virada com o dedo e a ação de acessibilidade). O painel da guiada não entra aqui.
 *
 * Quando o cabeçalho ocupa lugar (reserva), o conteúdo põe o espaço dele no `paddingTop` (ver [atualizarReserva]).
 * Quando a reserva muda, a primeira linha da página fica no lugar (ver [manterAPrimeiraLinha]). Sem animação: a regra é
 * só de visibilidade.
 */
internal class TelaCheiaDoLeitor(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
    private val cabecalho: View,
    private val aoGesto: (GestoDasBarras) -> Unit,
) {
    // Contêiner do texto: o paddingTop dele é a reserva do cabeçalho sobreposto.
    private val conteudo: View = activity.findViewById(R.id.conteudoDoLeitor)

    // Último valor de reserva aplicado. Começa falso: o coordenador aplica o primeiro estado logo no início.
    private var reservado = false
    private var dicaTentada = false
    private val limiarPx = activity.dpToPx(LIMIAR_DO_DESLIZE_DP).toFloat()
    private val limiarDescerPx = activity.dpToPx(RegraDasBarrasDoLeitor.LIMIAR_DESCER_DP)
    private val limiarSubirPx = activity.dpToPx(RegraDasBarrasDoLeitor.LIMIAR_SUBIR_DP)

    // A setinha que mostra e recolhe o cabeçalho no vertical (rb-66, passo 4).
    private val setinha = SetinhaDasBarras(
        activity.findViewById<ImageButton>(R.id.btnMostrarBarras),
        cabecalho,
        aoGesto,
    )

    // Rolagem com o dedo no vertical (rb-66, passo 4). Conta só o que o dedo andou: DRAGGING liga e IDLE desliga,
    // então o fling que vem do dedo (SETTLING) conta, e a rolagem do código (guiada, saltos, smoothScrollBy) não.
    private val rolagemComODedo = object : RecyclerView.OnScrollListener() {
        private var rolandoComDedo = false
        private var acumulado = 0

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> rolandoComDedo = true
                RecyclerView.SCROLL_STATE_IDLE -> {
                    rolandoComDedo = false
                    acumulado = 0
                }
            }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            // No modo páginas a virada com o dedo já avisa (aoVirarPeloDedo): a rolagem da lista não conta.
            if (!rolandoComDedo || lista.paginacao?.ativa == true) return
            val r = RegraDasBarrasDoLeitor.rolagem(acumulado, dy, limiarDescerPx, limiarSubirPx)
            acumulado = r.acumulado
            when (r.sinal) {
                -1 -> aoGesto(GestoDasBarras.LEITURA_ANDOU)
                1 -> aoGesto(GestoDasBarras.ROLOU_PARA_CIMA)
            }
        }
    }

    /**
     * Liga os avisos da lista (gesto engolido pela página e virada pelo dedo), a reserva (a altura do cabeçalho muda
     * com a busca e com o texto do capítulo) e a ação de acessibilidade para mostrar o cabeçalho. Chamado uma vez, pelo
     * coordenador.
     */
    fun ligar() {
        lista.aoGestoDaPagina = { dx, dy -> aoDeslizar(dx, dy) }
        lista.aoVirarPeloDedo = { aoGesto(GestoDasBarras.LEITURA_ANDOU) }
        // Fora do layout: mudar o padding do irmão no meio do layout do Coordinator pode ser descartado (a ordem dos
        // filhos não é a do XML). Por post, a reserva segue a altura nova no quadro seguinte.
        cabecalho.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // A setinha desce pela altura nova do cabeçalho (a busca e o título mudam essa altura).
            setinha.atualizarPosicao()
            if (bottom - top != oldBottom - oldTop) conteudo.post { atualizarReserva() }
        }
        lista.addOnScrollListener(rolagemComODedo)
        // Acesso com interruptor e por voz: no modo páginas não há setinha, então a lista oferece a ação.
        ViewCompat.addAccessibilityAction(lista, activity.getString(R.string.cd_mostrar_barras)) { _, _ ->
            aoGesto(GestoDasBarras.MOSTRAR)
            true
        }
    }

    /**
     * Mostra ou esconde o cabeçalho e aplica a reserva. Barata: roda a cada atualização da tela, e só mexe quando o
     * valor muda. [manterLinha] diz se a lista estava e continua no modo páginas com texto: só então a 1ª linha é
     * mantida. Na troca de modo, edição ou comparação, o adapter refaz tudo, e quem chama passa falso.
     */
    fun aplicar(estado: EstadoDoCabecalho, manterLinha: Boolean) {
        setinha.aplicar(estado.setinha)
        val alvo = if (estado.visivel) View.VISIBLE else View.GONE
        if (cabecalho.visibility == alvo && reservado == estado.reservado) return
        if (reservado != estado.reservado && manterLinha) {
            lista.manterAPrimeiraLinha { mudar(alvo, estado.reservado) }
        } else {
            mudar(alvo, estado.reservado)
        }
        // A dica fala do deslize do modo páginas: no vertical (sem deslize para mostrar) ela não vale. Quem confere o
        // modo é o post (tentarDica): o modo do adapter só muda depois deste aplicar.
        if (!estado.visivel && lista.paginacao?.ligada == true) tentarDica()
    }

    private fun mudar(alvo: Int, reservar: Boolean) {
        reservado = reservar
        cabecalho.visibility = alvo
        atualizarReserva()
    }

    /**
     * A reserva é o `paddingTop` do conteúdo com a altura do cabeçalho quando ele ocupa lugar, e 0 quando é sobreposto
     * ou some. Só mexe quando o valor muda. Quando o cabeçalho acaba de voltar, a altura pode ainda ser a antiga: o
     * listener de layout do cabeçalho corrige logo depois.
     */
    private fun atualizarReserva() {
        val alvo = if (reservado && cabecalho.visibility == View.VISIBLE) cabecalho.height else 0
        if (conteudo.paddingTop != alvo) conteudo.updatePadding(top = alvo)
    }

    /** Deslize sobre a página (gesto que a página engoliu). Não conta com seleção ou menu abertos. */
    private fun aoDeslizar(dx: Float, dy: Float) {
        if (lista.textoEmUso) return
        when (RegraDasPaginasDoLeitor.deslizeVertical(dx, dy, limiarPx)) {
            1 -> aoGesto(GestoDasBarras.MOSTRAR)
            -1 -> aoGesto(GestoDasBarras.RECOLHER)
        }
    }

    /**
     * Dica da tela cheia, uma vez por abertura do Leitor. Por post, para medir a lista já com a altura nova e com o
     * modo do adapter atualizado (rb-66, passo 7). Só marca como tentada quando a paginação está em uso e o cabeçalho
     * recolhido, senão tenta de novo na próxima mudança.
     */
    private fun tentarDica() {
        if (dicaTentada) return
        lista.post {
            if (dicaTentada || cabecalho.visibility != View.GONE || lista.paginacao?.ativa != true) return@post
            dicaTentada = true
            CoachMark.tentar(activity, Dica.LEITOR_TELA_CHEIA, lista)
        }
    }
}

/**
 * Muda a visibilidade do cabeçalho (e a reserva dele) mantendo a primeira linha da página. Com a reserva mudando, a
 * lista muda de altura, e o LinearLayoutManager ancora no foco (um parágrafo focado fica no lugar) ou no primeiro item,
 * então o topo sairia do lugar. Guarda o primeiro item visível e o offset dele, roda [mudar] e pede a mesma posição
 * com [LeitorLayoutManager.fixarPrimeiraLinha], que não conta como salto. O pedido pendente tem prioridade sobre a
 * âncora do foco.
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
