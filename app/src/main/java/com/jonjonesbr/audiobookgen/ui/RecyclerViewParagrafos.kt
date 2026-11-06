package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * Lista de parágrafos do leitor. Existe para três coisas:
 *
 * - saber quando o TalkBack pede uma rolagem (ações de acessibilidade SCROLL_FORWARD/BACKWARD): o RecyclerView não
 *   avisa isso, e a leitura guiada precisa tratar essa rolagem como manual, igual ao arraste com o dedo. A rolagem
 *   automática do código não passa por aqui.
 * - saber quando há um toque em andamento (do DOWN ao UP/CANCEL) ou um menu de seleção aberto (rb-38). Enquanto
 *   isso, [textoEmUso] é verdadeiro e o adapter segura as repinturas de sincronia, que trocariam a view sob o dedo.
 * - rearmar a seleção de texto de cada parágrafo quando a view é anexada à janela (rb-38, ver [rearmarSelecao]).
 * - rodar, depois do toque, a descida que a barra da seleção pede quando cobre o trecho marcado (rb-48, ver
 *   [depoisDoToque]). A descida é translação, não rolagem: não dispara os listeners de rolagem. O alvo dela fica em
 *   [descidaDaSelecao], e o espaço depois do último parágrafo soma esse alvo (rb-51, ver [EspacoDaDescidaNoFim]).
 * - ignorar o pedido de mostrar trecho de um parágrafo em foco, no modo de toque, sem seleção (rb-55, ver
 *   [pedidoDeMostrarTrechoVale]), no requestChildRectangleOnScreen da própria lista.
 * - guardar o trecho selecionado quando o parágrafo sai da tela (rb-58, ver [guardada]), e limpá-lo com um toque
 *   curto (ver [toqueLimpaGuardada]). Um toque curto dentro do trecho traz a seleção de volta (rb-60, ver
 *   [toqueReabreGuardada]). Um toque que para um fling não limpa nem reabre: a conferência do IDLE que vem depois
 *   decide (rb-60b, ver [faixaParaReabrirSozinho] em SelecaoSemMenu).
 * - dar o foco do código sem rolar a lista, na janela de [focoSemRolar] (rb-60b): a reabertura do trecho pede o foco
 *   para o TextView, e o foco não pode rolar a lista até o parágrafo inteiro.
 * - no modo páginas (rb-26b, ver [paginacao]), cortar a linha parcial de baixo, virar a página com o arraste lateral e
 *   com as ações de rolagem da acessibilidade. O pedido do Editor de ficar com o gesto (toque longo) tira o arraste
 *   lateral dele (ver [editorDonoDoGesto]).
 * - avisar, no modo páginas, os gestos que a página engoliu ([aoGestoDaPagina]) e as viradas pelo dedo
 *   ([aoVirarPeloDedo]), para a tela cheia do Leitor (rb-26b, passo 6, ver [TelaCheiaDoLeitor]).
 */
// 12 funções, limite 10: o modo páginas (rb-26b) fica na própria lista (clip e pedido do Editor).
@Suppress("TooManyFunctions")
class RecyclerViewParagrafos @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = androidx.recyclerview.R.attr.recyclerViewStyle,
) : RecyclerView(context, attrs, defStyle) {

    /** Chamado antes de a rolagem pedida pela acessibilidade acontecer. */
    var aoRolagemDeAcessibilidade: (() -> Unit)? = null

    /** Chamado (por post) quando o texto deixa de estar em uso: o adapter aplica as repinturas seguradas. */
    var aoLiberarTexto: (() -> Unit)? = null

    /** Chamado (por post, antes de [aoLiberarTexto]) para conferir a seleção viva e mostrar ou esconder a barra. */
    var aoConferirSelecao: (() -> Unit)? = null

    /** Chamado (por post, depois de [aoLiberarTexto]) quando o texto é liberado: o modo páginas alinha (rb-26b). */
    internal var aoLiberarPagina: (() -> Unit)? = null

    /**
     * Fim de um gesto de um dedo que a página engoliu (rb-26b, passo 6): o deslocamento do DOWN ao UP, em px. Só
     * dispara no modo páginas, e não para o gesto que o Editor pediu para si.
     */
    internal var aoGestoDaPagina: ((dx: Float, dy: Float) -> Unit)? = null

    /** A página virou com o arraste lateral de quem lê (rb-26b, passo 6). Não dispara para tecla nem TalkBack. */
    internal var aoVirarPeloDedo: (() -> Unit)? = null

    /**
     * Alvo da descida da barra da seleção (rb-48), sem a animação. Só a BarraDaSelecaoDeTexto escreve. O espaço depois
     * do último parágrafo (rb-51) soma este valor quando a barra está aberta, então ele não lê o translationY, que muda
     * na animação.
     */
    internal var descidaDaSelecao: Int = 0

    /**
     * Se a barra da seleção está embaixo, no lugar do painel da guiada (rb-26b, passo 3). Só a BarraDaSelecaoDeTexto
     * escreve. Quem lê: a GuidedReadingBarController, para não repor o painel VISIBLE enquanto ele está cedido, e o
     * ReaderTelaController, que conta a barra no respiro da lista.
     */
    internal var baseCedidaASelecao: Boolean = false

    /** Refaz o espaço do fim quando a descida muda (rb-51). Quem liga é o ReaderTelaController. */
    internal var aoMudarDescida: (() -> Unit)? = null

    /** Modo páginas do Leitor (rb-26b), ligado pelo ReaderRolagemController. Nulo ou inativo: a vertical normal. */
    internal var paginacao: PaginacaoDoLeitor? = null

    /** Há dedo na lista, do ACTION_DOWN até o UP ou o CANCEL. */
    var toqueEmAndamento = false
        private set

    /** Ação da barra da seleção que espera o toque terminar (rb-48). Só guarda a última. */
    private var acaoDepoisDoToque: (() -> Unit)? = null

    /** Filho da lista que estava sob o dedo no ACTION_DOWN (rb-53). Nulo sem toque. */
    private var filhoSobODedo: View? = null

    /**
     * O Editor pediu o gesto para si, no toque longo ou no duplo toque, com o toque em andamento (rb-26b, ver
     * [requestDisallowInterceptTouchEvent]). O arraste lateral da página deixa o gesto para ele. Zerada no DOWN.
     */
    internal var editorDonoDoGesto = false

    /** Trecho guardado quando o parágrafo selecionado sai da tela (rb-58). Só a barra e o toque olham para ele. */
    internal val guardada: SelecaoGuardada = SelecaoGuardada()

    /** Onde o dedo desceu no ACTION_DOWN, para saber se o toque foi curto (rb-58). */
    private var descidaX = 0f
    private var descidaY = 0f

    /** Havia trecho guardado no ACTION_DOWN (rb-58). */
    private var guardadaNoDown = false

    /**
     * A lista estava parada no ACTION_DOWN (rb-60): um toque que para um fling não reabre o trecho.
     */
    private var listaParadaNoDown = false

    /**
     * Toque curto que terminou com trecho guardado: a conferência da barra limpa o trecho e consome esta marca (rb-58).
     * Por ser curto, não lê o parágrafo (ver [selecaoDeTextoAberta]).
     */
    internal var toqueLimpaGuardada: Boolean = false

    /**
     * Toque curto dentro do trecho guardado (rb-60): a conferência tira o trecho e pede a seleção nativa dele de volta.
     * Guarda o próprio trecho, para a conferência não ter de medir o toque de novo.
     */
    internal var toqueReabreGuardada: IntRange? = null

    /** Janela em que o foco dado pelo código não rola a lista (rb-60b). Só a reabertura do trecho a liga. */
    internal var focoSemRolar: Boolean = false

    /**
     * Se [v] é o item que o dedo tocou, enquanto o toque dura (rb-53). O adapter não reescreve o texto desse item
     * nesse meio-tempo: trocar o texto sob o dedo faz o Editor perder a posição do toque longo.
     */
    fun sobODedo(v: View): Boolean = toqueEmAndamento && filhoSobODedo === v

    /** Quantos menus de seleção de texto estão abertos agora (o adapter conta). */
    var selecoesAbertas = 0
        private set

    /**
     * Verdadeiro enquanto há toque, menu de seleção ou texto selecionado com foco: as repinturas de sincronia
     * esperam. A regra é larga (rb-54): vale para qualquer TextView com foco, inclusive o campo da edição e o texto
     * da comparação de alterações, cuja seleção também não pode ser trocada sob o dedo.
     * O texto selecionado cobre o intervalo entre o UP do toque longo e a abertura do menu (o Editor a abre depois).
     */
    val textoEmUso: Boolean
        get() = segurarRepinturaDeSincronia(toqueEmAndamento, selecoesAbertas, focoComSelecao)

    /**
     * Seleção de texto agora, com a regra larga de [textoEmUso]: menu aberto, ou texto já marcado com foco (antes de
     * o menu abrir). Com trecho guardado (rb-58) também: o toque que o limpa não pode ler o parágrafo. Usada no toque.
     * Quem decide a barra da seleção usa [textoComSelecaoViva], que é mais restrita.
     */
    val selecaoDeTextoAberta: Boolean
        get() = selecoesAbertas > 0 || focoComSelecao || guardada.ativa

    /** Algum TextView com foco tem texto selecionado. Seleção sem foco não conta: o foco que sai a desfaz. */
    private val focoComSelecao: Boolean
        get() = (findFocus() as? TextView)?.hasSelection() == true

    /**
     * Seleção viva da barra (rb-54): o TextView de leitura com foco e com seleção. Seleção sem foco não conta: o
     * foco que sai a desfaz (ver ParagrafoAdapter.VHLeitura). Só o parágrafo de leitura entra, o que liga o
     * [MenuDaSelecaoDeTexto]: a edição e a comparação de alterações usam o menu do sistema, e a barra do
     * Leitor não é delas. Nulo sem seleção viva.
     */
    internal val textoComSelecaoViva: TextView?
        get() = (findFocus() as? TextView)?.takeIf {
            it.hasSelection() && it.customSelectionActionModeCallback is MenuDaSelecaoDeTexto
        }

    /**
     * Roda [acao] quando o toque em andamento terminar (rb-48). Sem toque, roda já. Assim a lista não muda de
     * posição sob o dedo: o toque longo abre o menu com o dedo ainda na tela, e um arraste depois dele
     * selecionaria as linhas erradas. Se mais de uma ação chegar no mesmo toque, vale a última.
     */
    fun depoisDoToque(acao: () -> Unit) {
        if (toqueEmAndamento) acaoDepoisDoToque = acao else acao()
    }

    fun abriuSelecao() {
        selecoesAbertas++
    }

    fun fechouSelecao() {
        selecoesAbertas = (selecoesAbertas - 1).coerceAtLeast(0)
        liberarDepois()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            toqueEmAndamento = true
            editorDonoDoGesto = false
            filhoSobODedo = findChildViewUnder(ev.x, ev.y)
            descidaX = ev.x
            descidaY = ev.y
            guardadaNoDown = guardada.ativa
            // Antes do super: a lista ainda não reagiu ao toque (o que para um fling muda o estado).
            listaParadaNoDown = scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        val tratado = paginacao?.despacharToque(ev) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(ev)
        val fimDoToque = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (toqueEmAndamento && fimDoToque) {
            // Seleção aberta agora (duplo toque, toque longo) não conta: ela substitui o trecho na conferência.
            val curtoNoGuardado = guardadaNoDown && ev.actionMasked == MotionEvent.ACTION_UP && ehToqueCurto(
                duracaoMs = ev.eventTime - ev.downTime,
                deslocamentoPx = hypot((ev.x - descidaX).toDouble(), (ev.y - descidaY).toDouble()).toFloat(),
                slopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                limiteDoToqueLongoMs = ViewConfiguration.getLongPressTimeout().toLong(),
                selecaoAberta = selecoesAbertas > 0 || focoComSelecao,
            )
            // Dentro do trecho, o toque reabre a seleção (rb-60); fora, limpa o trecho (rb-58).
            val faixa = if (curtoNoGuardado) {
                faixaDoToqueNoGuardado(filhoSobODedo, descidaX, descidaY, listaParadaNoDown)
            } else {
                null
            }
            toqueReabreGuardada = faixa
            // Toque que para um fling (lista não parada no DOWN) não limpa: a conferência do IDLE decide (rb-60b).
            toqueLimpaGuardada = curtoNoGuardado && faixa == null && listaParadaNoDown
            toqueEmAndamento = false
            filhoSobODedo = null
            liberarDepois()
            // Por post: o UP ainda termina dentro do Editor, e a descida não pode mexer na lista no meio desse
            // despacho.
            val acao = acaoDepoisDoToque
            acaoDepoisDoToque = null
            if (acao != null) post { acao() }
        }
        return tratado
    }

    /**
     * O Editor pede o gesto para si no toque longo e no duplo toque (rb-26b). Com toque em andamento, marca que o
     * arraste lateral da página deve largar o gesto; senão ele engoliria a seleção que o Editor estende.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
        if (disallowIntercept && toqueEmAndamento) editorDonoDoGesto = true
    }

    /**
     * Porta única de "confira a seleção viva e solte as repinturas" (rb-38, rb-54). Por post: o fim do toque e o
     * fechar da seleção vêm de dentro do layout ou do próprio toque, e repintar ali daria IllegalStateException.
     */
    internal fun liberarDepois() {
        post {
            aoConferirSelecao?.invoke()
            if (!textoEmUso) {
                aoLiberarTexto?.invoke()
                aoLiberarPagina?.invoke()
            }
        }
    }

    /**
     * Pedido de "mostrar este trecho" de um filho (rb-55). Só o parágrafo de leitura entra na regra: edição e
     * comparação sempre passam. Limites aceitos: fora do modo de toque (D-pad) o setText ainda pode puxar a lista, e
     * um ACTION_SHOW_ON_SCREEN do TalkBack sobre o parágrafo em foco é ignorado.
     */
    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean {
        val tv = (getChildViewHolder(child) as? ParagrafoAdapter.VHLeitura)?.tv
        if (tv != null && !pedidoDeMostrarTrechoVale(child.isInTouchMode, tv.isFocused, tv.hasSelection())) return false
        return super.requestChildRectangleOnScreen(child, rect, immediate)
    }

    /**
     * Antes de o RecyclerView tirar o filho da tela. O unFocus que vem depois desfaria a seleção do parágrafo com foco
     * (rb-58): por isso o trecho é guardado aqui, na hora.
     */
    override fun onChildDetachedFromWindow(child: View) {
        guardarTrechoAoSair(child)
        super.onChildDetachedFromWindow(child)
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        (getChildViewHolder(child) as? ParagrafoAdapter.VHLeitura)?.tv?.rearmarSelecao()
    }

    /** Desenha só até o corte no modo páginas (rb-26b): a linha parcial de baixo some. Sem paginação, é o padrão. */
    override fun dispatchDraw(canvas: Canvas) {
        val p = paginacao
        if (p == null || !p.ativa) {
            super.dispatchDraw(canvas)
            return
        }
        val salvo = canvas.save()
        // O clip de cima também: com clipToPadding falso, a última linha da página anterior apareceria no respiro.
        canvas.clipRect(0, topoDaPagina(), width, p.corte())
        super.dispatchDraw(canvas)
        canvas.restoreToCount(salvo)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val rolagem = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (rolagem) {
            aoRolagemDeAcessibilidade?.invoke()
            val paginada = paginacao?.takeIf { it.ativa }
            if (paginada != null) {
                val sentido = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1
                return paginada.virar(sentido)
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

/**
 * Guarda o trecho marcado no parágrafo com foco quando ele sai da tela (rb-58). Função de topo: a classe da lista já
 * está acima do limite de funções do detekt (ver o @Suppress). Pinta logo o span marcador, porque a view pode voltar
 * do cache sem bind.
 * Holder invalidado (notifyDataSetChanged, lista nova) não guarda: o trecho seria de outro texto. Isso já sai do
 * próprio `bindingAdapterPosition`, que devolve NO_POSITION para holder invalidado ou removido (o `isInvalid` do
 * ViewHolder é package-private, por isso não é checado aqui).
 */
internal fun RecyclerViewParagrafos.guardarTrechoAoSair(child: View) {
    val holder = getChildViewHolder(child) as? ParagrafoAdapter.VHLeitura ?: return
    val tv = holder.tv
    val trecho = trechoSelecionado(tv.selectionStart, tv.selectionEnd, tv.text)
    val posicao = holder.bindingAdapterPosition
    if (trecho == null || posicao == RecyclerView.NO_POSITION || !guardarAoSairDaTela(true, tv.isFocused, true)) return
    guardada.guardar(posicao, trecho, tv.text.toString())
    tv.pintarTrechoGuardado(guardada.trechoNoParagrafo(posicao, tv.text.toString()))
}

/**
 * Faixa do trecho guardado que um toque curto tocou (rb-60), ou nulo. O TextView é o do item sob o dedo no DOWN. O
 * offset sai do ponto do DOWN em coordenadas do TextView: o offsetRect desconta a posição e a rolagem de cada pai.
 * Função de topo, fora da contagem de funções da classe.
 */
internal fun RecyclerViewParagrafos.faixaDoToqueNoGuardado(
    filho: View?,
    x: Float,
    y: Float,
    listaParada: Boolean,
): IntRange? {
    // Filho que já saiu desta lista no meio do toque não é medido (getChildViewHolder e offsetRect lançariam).
    val holder = filho?.takeIf { it.parent === this }?.let { getChildViewHolder(it) } as? ParagrafoAdapter.VHLeitura
        ?: return null
    val tv = holder.tv
    // Lista que rolava no DOWN não mede: quem para o fling é a lista, e o toque nem reabre (ver toqueReabreTrecho).
    val offset = if (listaParada && tv.isAttachedToWindow) offsetDoToqueNoTexto(tv, x, y) else -1
    return toqueReabreTrecho(holder.bindingAdapterPosition, offset, listaParada, guardada, tv.text.toString())
}

/**
 * Offset do caractere sob o ponto ([x], [y]) da lista, medido no [tv] (rb-60). Sem linhas no layout, devolve -1.
 */
private fun RecyclerViewParagrafos.offsetDoToqueNoTexto(tv: TextView, x: Float, y: Float): Int {
    val ponto = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
    offsetRectIntoDescendantCoords(tv, ponto)
    return tv.getOffsetForPosition(ponto.left.toFloat(), ponto.top.toFloat())
}
