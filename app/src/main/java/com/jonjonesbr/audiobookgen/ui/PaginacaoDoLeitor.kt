package com.jonjonesbr.audiobookgen.ui

import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.domain.LinhaDaPagina
import com.jonjonesbr.audiobookgen.domain.RegraDasPaginasDoLeitor

/**
 * Modo páginas do Leitor (rb-26b): páginas virtuais sobre a lista vertical. A lista continua com o mesmo adapter e um
 * TextView por parágrafo. A linha parcial de baixo some com um clip no dispatchDraw da lista ([corte]), e virar
 * a página é um deslocamento instantâneo ([virar]). Criada e mantida pelo ReaderRolagemController.
 *
 * [ligada] é a opção da Aparência; [ativa] acrescenta o modo de leitura, então edição e comparação de alterações
 * continuam na vertical normal. Com [ativa] falso, a lista não corta, não vira e não muda o toque.
 */
internal class PaginacaoDoLeitor(
    private val lista: RecyclerViewParagrafos,
    private val emModoLeitura: () -> Boolean,
    // O que a rolagem de quem lê faz hoje quando a lista muda (progresso e barra da guiada).
    private val aoVirar: () -> Unit,
    // Virada pedida por quem lê (gesto, tecla, TalkBack): liga o modo manual da leitura guiada.
    private val aoVirarPeloLeitor: () -> Unit,
) {
    /** Serviço de acessibilidade: com o TalkBack ligado a lista é vertical (rb-67, item 2). */
    private val acessibilidade: AccessibilityManager? = lista.context.getSystemService(AccessibilityManager::class.java)

    private val leitorDeTela: Boolean
        get() = acessibilidade?.isTouchExplorationEnabled == true

    /**
     * Opção "Horizontal (páginas)" ligada. Trocar o valor redesenha a lista (o corte muda). Com o TalkBack ligado, a
     * opção fica guardada mas não vale: quem ouve não ganha nada com páginas cortadas por desenho (rb-67, item 2).
     */
    var ligada: Boolean = false
        get() = field && !leitorDeTela
        set(valor) {
            if (field == valor) return
            field = valor
            lista.invalidate()
        }

    /** Paginação em uso agora: a opção ligada e o modo de leitura. */
    val ativa: Boolean
        get() = ligada && emModoLeitura()

    /** Última base de página medida, para redesenhar quando o padding de baixo muda (barra da guiada, giro). */
    private var ultimaBase = -1

    /** Volta da página em curso: até onde a página anterior desenha, para não repetir linha (rb-67, item 1). */
    private val volta = VoltaDaPagina(lista)

    /** Há topo desalinhado esperando o texto ser liberado (toque ou menu aberto), para alinhar depois (rb-26b, D2). */
    private var alinharPendente = false

    /**
     * Gancho de uma vez só, roda depois do alinhamento do próximo salto (rb-26b): o botão "Voltar à narração" o usa
     * para levar a página até a linha narrada. Fora das páginas, [aoTerminarSalto] o descarta.
     */
    var depoisDoProximoSalto: (() -> Unit)? = null

    private val arraste = ArrasteLateralDaPagina(
        lista,
        podeVirar = { ativa },
        // Por post: a virada roda depois do UP, fora do despacho do toque. A conferência da seleção (rb-60b) vê a
        // página nova no liberarDepois que vem depois.
        // Só o arraste avisa a tela cheia (rb-26b, passo 6): tecla, TalkBack e a guiada não recolhem o cabeçalho.
        aoCompletar = { paraFrente ->
            lista.post { if (virarPeloLeitor(if (paraFrente) 1 else -1)) lista.aoVirarPeloDedo?.invoke() }
        },
    )

    init {
        lista.viewTreeObserver.addOnGlobalLayoutListener {
            // A troca de padding (barra que aparece ou some) nem sempre redesenha os filhos: sem isto, o corte
            // desenhado e o passo da próxima virada não bateriam.
            if (ligada && base() != ultimaBase) {
                ultimaBase = base()
                lista.invalidate()
            }
            // Layout que deixa o topo no meio de uma linha (a área de baixo encolhe e o parágrafo com foco fica no
            // lugar): alinha de novo. Com texto em uso, espera a liberação (ver aoLiberarPagina). No fim do
            // conteúdo não alinha: o corte da última página é inevitável, e alinhar esconderia a última linha do livro.
            if (ativa && lista.canScrollVertically(1) && ajusteDoTopoAtual() != 0) {
                if (lista.textoEmUso) alinharPendente = true else lista.post { alinharTopo() }
            }
        }
        lista.aoLiberarPagina = {
            if (alinharPendente) {
                alinharPendente = false
                alinharTopo()
            }
        }
    }

    /** Altura da página na lista: até onde o texto pode aparecer, sem o padding de baixo (barra, mini player). */
    private fun base(): Int = lista.height - lista.paddingBottom

    /** Ajuste do topo agora (ver [RegraDasPaginasDoLeitor.ajusteDoTopo]), com a altura da página desta lista. */
    private fun ajusteDoTopoAtual(): Int =
        RegraDasPaginasDoLeitor.ajusteDoTopo(lista.linhasNaTela(), lista.paddingTop, base() - lista.paddingTop)

    /**
     * Até onde a página desenha. O filtro é obrigatório: depois de virar sobre uma linha maior que a página, as linhas
     * de antes terminam acima do topo, e sem o filtro o corte ficaria acima dele e a página sairia em branco.
     */
    fun corte(): Int = RegraDasPaginasDoLeitor.corte(
        lista.linhasNaTela().filter { it.base > lista.paddingTop },
        volta.limite(base()),
    )

    /** Vira a página ([sentido] 1 para a frente, -1 para trás). Falso se não andou (fim ou início do livro). */
    fun virar(sentido: Int): Boolean {
        if (!ativa || lista.height <= 0) return false
        val andou = if (sentido > 0) {
            avancar(lista.paddingTop, volta.limite(base()))
        } else {
            voltar(lista.paddingTop, base())
        }
        if (andou) {
            // scrollBy não passa pelo IDLE: a conferência da seleção e o trecho guardado (rb-60b) rodam aqui.
            lista.liberarDepois()
            aoVirar()
        }
        return andou
    }

    /** Vira a página por pedido de quem lê: além de [virar], liga o modo manual da leitura guiada. */
    fun virarPeloLeitor(sentido: Int): Boolean {
        val andou = virar(sentido)
        if (andou) aoVirarPeloLeitor()
        return andou
    }

    private fun avancar(topo: Int, fundo: Int): Boolean {
        val passo = RegraDasPaginasDoLeitor.passoParaFrente(lista.linhasNaTela(), topo, fundo)
        // Sem linha escondida, a lista pode rolar: o próximo parágrafo ainda não está anexado. Nesse caso anda uma
        // página e alinha o topo na primeira linha inteira. Fim do livro: o último item está na tela, e o que sobra
        // para rolar é só o padding de baixo (nada a fazer).
        val ultimoNaTela = (lista.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
        val fimDoLivro = ultimoNaTela >= (lista.adapter?.itemCount ?: 0) - 1
        val podeAndar = passo > 0 || (!fimDoLivro && lista.canScrollVertically(1))
        if (passo > 0) {
            // Com passo, só anda o passo. Ajustar o topo depois de avançar sobre linha maior que a página oscilaria.
            lista.scrollBy(0, passo)
        } else if (podeAndar) {
            lista.scrollBy(0, fundo - topo)
            val ajuste = RegraDasPaginasDoLeitor.ajusteDepoisDeVoltar(lista.linhasNaTela(), topo)
            if (ajuste > 0) lista.scrollBy(0, ajuste)
        }
        return podeAndar
    }

    private fun voltar(topo: Int, fundo: Int): Boolean {
        if (!lista.canScrollVertically(-1)) return false
        volta.antesDeVoltar()
        lista.scrollBy(0, -(fundo - topo))
        // Só ajusta se ainda dá para subir: no início do livro, o ajuste tiraria o respiro do primeiro parágrafo.
        if (lista.canScrollVertically(-1)) {
            val ajuste = RegraDasPaginasDoLeitor.ajusteDepoisDeVoltar(lista.linhasNaTela(), topo)
            if (ajuste > 0) lista.scrollBy(0, ajuste)
        }
        // Depois dos dois deslocamentos: mede onde ficou a linha que estava no topo, para limitar a página da volta.
        volta.depoisDeVoltar()
        return true
    }

    /**
     * Alinha o topo na primeira linha inteira, depois de um salto (busca, capítulo, marcação, posição salva, chip).
     * Não roda com toque, menu ou foco com seleção: o Editor é dono da lista nesse momento. No fim do conteúdo (a
     * lista não rola para a frente) também não roda: o topo cortado ali é inevitável, e alinhar esconderia a última
     * linha.
     */
    fun alinharTopo() {
        if (!ativa || lista.textoEmUso || !lista.canScrollVertically(1)) return
        val ajuste = ajusteDoTopoAtual()
        if (ajuste != 0) lista.scrollBy(0, ajuste)
    }

    /**
     * Chamado pelo LeitorLayoutManager quando um salto termina o layout. Por post: o layout não pode rolar a lista.
     * Se outro salto ficou pedido antes desse post (ex.: restaurarPosicaoSeNecessario ao recriar a tela), o gancho
     * espera o fim dele, porque o layout dele desfaria o alinhamento.
     */
    fun aoTerminarSalto() {
        val depois = depoisDoProximoSalto
        depoisDoProximoSalto = null
        if (!ativa) return
        lista.post {
            alinharTopo()
            val outroSalto = (lista.layoutManager as? LeitorLayoutManager)?.temSaltoPendente == true
            if (outroSalto) {
                if (depoisDoProximoSalto == null) depoisDoProximoSalto = depois
            } else {
                depois?.invoke()
            }
        }
    }

    /** Toque da lista no modo páginas: o arraste lateral vira a página, e o resto do gesto é engolido. */
    fun despacharToque(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean =
        if (ligada) arraste.despachar(ev, despachar) else despachar(ev)
}

/**
 * Linhas dos parágrafos na tela, nas coordenadas da lista, ordenadas pelo topo (rb-26b). Só entra o TextView de leitura
 * (no modo vertical o item é o próprio TextView). A conta é de O(log n) por parágrafo, mais as linhas visíveis.
 */
internal fun RecyclerViewParagrafos.linhasNaTela(): List<LinhaDaPagina> =
    (0 until childCount)
        .mapNotNull { getChildViewHolder(getChildAt(it)) as? ParagrafoAdapter.VHLeitura }
        // Só o TextView direto da linha conta: holder com outra View por cima é ignorado.
        .filter { it.itemView === it.tv }
        .flatMap { linhasDoParagrafo(it.tv) }
        .sortedBy { it.topo }

/** Linhas de um parágrafo visível, nas coordenadas da lista. Vazio sem layout do texto. */
private fun RecyclerViewParagrafos.linhasDoParagrafo(tv: TextView): List<LinhaDaPagina> {
    val layout = tv.layout ?: return emptyList()
    val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
    val primeira = layout.getLineForVertical((-y0).coerceAtLeast(0))
    val ultima = layout.getLineForVertical((height - y0).coerceAtLeast(0))
    return (primeira..ultima).map { linha ->
        LinhaDaPagina(y0 + layout.getLineTop(linha), y0 + layout.getLineBottom(linha))
    }
}

/**
 * Leva a página até a linha do [progressPct] do parágrafo [indice] (rb-26b). O salto leva só ao início do parágrafo, e
 * a linha narrada pode estar em outra página. Chamado depois do layout do salto (por post, ver [aoTerminarSalto]).
 * Falso se não há paginação em uso, texto em uso ou layout do parágrafo.
 */
internal fun RecyclerViewParagrafos.levarALinhaNarrada(indice: Int, progressPct: Float): Boolean {
    val pag = paginacao?.takeIf { it.ativa && !textoEmUso }
    val tv = (findViewHolderForAdapterPosition(indice) as? ParagrafoAdapter.VHLeitura)
        ?.takeIf { it.itemView === it.tv }?.tv
    val linha = tv?.let { linhaDoProgresso(it, progressPct) }
    if (pag == null || linha == null) return false
    // Mesma regra da guiada ao virar a página, com subida: a linha narrada vai ao topo e a página fica alinhada.
    val passo = RegraDasPaginasDoLeitor.passoDaGuiada(linha.topo, linha.base, paddingTop, pag.corte(), podeSubir = true)
    // scrollBy por código não passa por DRAGGING: não liga o modo manual nem a tela cheia.
    if (passo != 0) scrollBy(0, passo)
    return true
}

/**
 * Linha do [progressPct] do texto do [tv], nas coordenadas da lista (rb-26b). Mesma conta de offset de
 * GuidedReadingBarController.rolarParaDestaque. Nulo sem layout ou sem texto.
 */
internal fun linhaDoProgresso(tv: TextView, progressPct: Float): LinhaDaPagina? {
    val layout = tv.layout
    val tamanho = tv.text.length
    if (layout == null || tamanho == 0) return null
    val offset = (tamanho * progressPct).toInt().coerceIn(0, tamanho - 1)
    val linha = layout.getLineForOffset(offset)
    val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
    return LinhaDaPagina(y0 + layout.getLineTop(linha), y0 + layout.getLineBottom(linha))
}
