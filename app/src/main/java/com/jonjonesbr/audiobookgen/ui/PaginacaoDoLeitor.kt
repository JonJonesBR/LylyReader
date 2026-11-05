package com.jonjonesbr.audiobookgen.ui

import android.view.MotionEvent
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
    /** Opção "Horizontal (páginas)" ligada. Trocar o valor redesenha a lista (o corte muda). */
    var ligada: Boolean = false
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

    private val arraste = ArrasteLateralDaPagina(
        lista,
        podeVirar = { ativa },
        // Por post: a virada roda depois do UP, fora do despacho do toque. A conferência da seleção (rb-60b) vê a
        // página nova no liberarDepois que vem depois.
        aoCompletar = { paraFrente -> lista.post { virarPeloLeitor(if (paraFrente) 1 else -1) } },
    )

    init {
        lista.viewTreeObserver.addOnGlobalLayoutListener {
            // A troca de padding (barra que aparece ou some) nem sempre redesenha os filhos: sem isto, o corte
            // desenhado e o passo da próxima virada não bateriam.
            if (ligada && base() != ultimaBase) {
                ultimaBase = base()
                lista.invalidate()
            }
        }
    }

    /** Altura da página na lista: até onde o texto pode aparecer, sem o padding de baixo (barra, mini player). */
    private fun base(): Int = lista.height - lista.paddingBottom

    /**
     * Até onde a página desenha. O filtro é obrigatório: depois de virar sobre uma linha maior que a página, as linhas
     * de antes terminam acima do topo, e sem o filtro o corte ficaria acima dele e a página sairia em branco.
     */
    fun corte(): Int = RegraDasPaginasDoLeitor.corte(
        lista.linhasNaTela().filter { it.base > lista.paddingTop },
        base(),
    )

    /** Vira a página ([sentido] 1 para a frente, -1 para trás). Falso se não andou (fim ou início do livro). */
    fun virar(sentido: Int): Boolean {
        if (!ativa || lista.height <= 0) return false
        val andou = if (sentido > 0) avancar(lista.paddingTop, base()) else voltar(lista.paddingTop, base())
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
        lista.scrollBy(0, -(fundo - topo))
        // Só ajusta se ainda dá para subir: no início do livro, o ajuste tiraria o respiro do primeiro parágrafo.
        if (lista.canScrollVertically(-1)) {
            val ajuste = RegraDasPaginasDoLeitor.ajusteDepoisDeVoltar(lista.linhasNaTela(), topo)
            if (ajuste > 0) lista.scrollBy(0, ajuste)
        }
        return true
    }

    /**
     * Alinha o topo na primeira linha inteira, depois de um salto (busca, capítulo, marcação, posição salva, chip).
     * Não roda com toque, menu ou foco com seleção: o Editor é dono da lista nesse momento.
     */
    fun alinharTopo() {
        if (!ativa || lista.textoEmUso) return
        val ajuste = RegraDasPaginasDoLeitor.ajusteDoTopo(lista.linhasNaTela(), lista.paddingTop)
        if (ajuste != 0) lista.scrollBy(0, ajuste)
    }

    /** Chamado pelo LeitorLayoutManager quando um salto termina o layout. Por post: o layout não pode rolar a lista. */
    fun aoTerminarSalto() {
        if (ativa) lista.post { alinharTopo() }
    }

    /** Toque da lista no modo páginas: o arraste lateral vira a página, e o resto do gesto é engolido. */
    fun despacharToque(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean =
        if (ligada) arraste.despachar(ev, despachar) else despachar(ev)
}

/**
 * Linhas dos parágrafos na tela, nas coordenadas da lista, ordenadas pelo topo (rb-26b). Só entra o TextView de leitura
 * (no modo vertical o item é o próprio TextView). A conta é de O(log n) por parágrafo, mais as linhas visíveis.
 */
internal fun RecyclerViewParagrafos.linhasNaTela(): List<LinhaDaPagina> {
    val linhas = mutableListOf<LinhaDaPagina>()
    for (i in 0 until childCount) {
        val holder = getChildViewHolder(getChildAt(i)) as? ParagrafoAdapter.VHLeitura ?: continue
        val tv = holder.tv
        val layout = tv.layout ?: continue
        if (holder.itemView !== tv) continue
        val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
        val primeira = layout.getLineForVertical((-y0).coerceAtLeast(0))
        val ultima = layout.getLineForVertical((height - y0).coerceAtLeast(0))
        for (linha in primeira..ultima) {
            linhas.add(LinhaDaPagina(y0 + layout.getLineTop(linha), y0 + layout.getLineBottom(linha)))
        }
    }
    return linhas.sortedBy { it.topo }
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
