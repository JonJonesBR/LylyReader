package com.jonjonesbr.audiobookgen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.RegraDaPaginaDoParagrafo

/**
 * Página de um parágrafo na rolagem horizontal (rb-26a). Um parágrafo longo não cabe na altura da página; esta
 * página rola na vertical, dentro dela, para o texto sair inteiro. O TextView de [item_paragrafo] fica dentro, como
 * filho único, e continua sendo quem mostra o texto, seleciona e recebe o toque longo.
 *
 * Por que a rolagem não fica no próprio TextView: a leitura guiada repinta o parágrafo ~20 vezes por segundo
 * (setText), e cada setText zeraria a rolagem. Aqui a página rola e o texto só muda por dentro.
 *
 * A página volta ao topo só quando o adapter a põe com outro parágrafo ([mostrar]). Não há reset ao sair da tela:
 * um notify no mesmo parágrafo (Destacar, busca) mantém a rolagem de quem lê.
 *
 * Na rolagem vertical não há esta página: o item continua sendo o TextView (ver ParagrafoAdapter).
 */
class PaginaDoParagrafo @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** O TextView do parágrafo, dentro da página. */
    lateinit var texto: TextView
        private set

    /** Parágrafo que a página mostra agora (ver [mostrar]). */
    private var posicaoMostrada = RecyclerView.NO_POSITION

    // Não dá para pôr isto no XML (nestedScrollingEnabled="false"): o construtor do View lê o atributo antes de a
    // NestedScrollView montar o mChildHelper, e o setter sobrescrito cairia num NPE. Aqui o super já terminou.
    init {
        isNestedScrollingEnabled = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        texto = findViewById(R.id.tvParagrafo)
    }

    // Os eventos de toque e da roda são olhados no dispatch, não no onTouchEvent, para não gerar o aviso de
    // acessibilidade do lint (o onTouchEvent abaixo só devolve falso num caso). A rolagem do acompanhamento (fora
    // do dispatch) não conta como manual.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val antes = scrollY
        val tratado = super.dispatchTouchEvent(ev)
        if (scrollY != antes) avisarRolagemPeloLeitor()
        return tratado
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val antes = scrollY
        val tratado = super.dispatchGenericMotionEvent(event)
        if (scrollY != antes) avisarRolagemPeloLeitor()
        return tratado
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val tratado = super.performAccessibilityAction(action, arguments)
        // A rolagem do NSV é animada, então aqui não dá para comparar o scrollY antes e depois.
        if (tratado && action in ACOES_DE_ROLAGEM) avisarRolagemPeloLeitor()
        return tratado
    }

    /**
     * Sem seleção, a página não pede para o TextView ficar visível: cada setText da leitura guiada, com o texto em
     * foco, pediria o offset 0 e a página voltaria ao topo 20 vezes por segundo. Com seleção aberta, a rolagem
     * automática ao arrastar as alças continua.
     */
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        if (!texto.hasSelection()) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }

    /**
     * Chamado pelo adapter a cada bind. A página volta ao topo só quando passa a mostrar outro parágrafo; um notify
     * no mesmo parágrafo (Destacar, busca, troca de lista) repinta sem mexer na rolagem de quem lê.
     */
    fun mostrar(posicao: Int) {
        if (posicao == posicaoMostrada) return
        posicaoMostrada = posicao
        scrollTo(0, 0)
    }

    /** Sem alcance, a página não participa do gesto: o arraste fica para a lista, como com o TextView sozinho. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!canScrollVertically(1) && !canScrollVertically(-1)) return false
        return super.onInterceptTouchEvent(ev)
    }

    /** Sem alcance, o toque inicial não é consumido aqui; ele sobe para a lista (ver onInterceptTouchEvent). */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !canScrollVertically(1) && !canScrollVertically(-1)) {
            return false
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Rola a página para a linha em leitura da guiada ([progressPct] é o progresso dentro do parágrafo), com a mesma
     * regra do acompanhamento vertical. Chamado a cada tick; o scrollBy já limita o alcance.
     */
    fun acompanharDestaque(progressPct: Float) {
        val layout = texto.layout ?: return
        val tamanho = texto.text.length
        val alturaVisivel = height - paddingTop - paddingBottom
        if (tamanho == 0 || alturaVisivel <= 0) return
        val offset = (tamanho * progressPct).toInt().coerceIn(0, tamanho - 1)
        val fimDaLinha = texto.top + texto.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(offset))
        val passo = RegraDaPaginaDoParagrafo.passoParaDestaque(fimDaLinha - scrollY, alturaVisivel)
        if (passo != 0) scrollBy(0, passo)
    }

    /** Rola uma tela para cima (-1) ou para baixo (1), com animação. Não usa pageScroll, que mexe no foco. */
    fun rolarUmaTela(sentido: Int) {
        smoothScrollBy(0, sentido * (height - paddingTop - paddingBottom))
    }

    companion object {
        fun inflar(inflater: LayoutInflater, parent: ViewGroup): PaginaDoParagrafo =
            inflater.inflate(R.layout.item_paragrafo_pagina, parent, false) as PaginaDoParagrafo

        private val ACOES_DE_ROLAGEM: Set<Int> = setOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            android.R.id.accessibilityActionScrollDown,
            android.R.id.accessibilityActionScrollUp,
        )
    }
}

/**
 * Quem lê rolou o texto dentro da página: a lista (dono do aviso) decide o que isso significa. Fica fora da classe
 * para a página não passar do limite de funções do detekt.
 */
private fun PaginaDoParagrafo.avisarRolagemPeloLeitor() {
    (parent as? RecyclerViewParagrafos)?.aoRolarDentroDaPagina?.invoke()
}

/**
 * Página inteira na tela com a lista parada (rolagem horizontal). Nulo na vertical, com a lista rolando ou entre
 * páginas. Não usa findViewHolderForAdapterPosition: com clipToPadding=false, o LinearLayoutManager também mantém
 * anexadas as páginas vizinhas na margem.
 */
internal fun RecyclerView.paginaParadaNaTela(): PaginaDoParagrafo? {
    val lm = layoutManager as? LinearLayoutManager ?: return null
    val parada = lm.orientation == LinearLayoutManager.HORIZONTAL && scrollState == RecyclerView.SCROLL_STATE_IDLE
    val posicao = if (parada) lm.findFirstCompletelyVisibleItemPosition() else RecyclerView.NO_POSITION
    return if (posicao == RecyclerView.NO_POSITION) null else lm.findViewByPosition(posicao) as? PaginaDoParagrafo
}
