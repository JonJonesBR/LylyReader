package com.jonjonesbr.audiobookgen.ui

/**
 * Uma linha do texto identificada pelo parágrafo (posição no adapter) e pelo índice dela no layout (rb-67, item 1).
 * [topo] é a borda de cima dela nas coordenadas da lista, no momento em que foi medida.
 */
internal data class AncoraDaLinha(val posicao: Int, val linha: Int, val topo: Int)

/**
 * Volta de uma página no modo páginas (rb-67, item 1). Antes de voltar, guarda a linha do topo (C). Depois dos dois
 * deslocamentos, mede onde C ficou e qual é a nova linha do topo (Q). Enquanto Q ainda é a linha do topo, o fundo da
 * página fica na altura de C: a página da volta termina na linha antes de C, sem repetir nem perder linha. Qualquer
 * rolagem que troque a linha do topo desfaz o limite sozinha.
 */
internal class VoltaDaPagina(private val lista: RecyclerViewParagrafos) {
    /** Linha do topo antes de voltar (C). */
    private var antesDaVolta: AncoraDaLinha? = null

    /** Linha do topo depois de voltar (Q), e quanto C ficou abaixo dela. Nulo fora de uma volta. */
    private var noTopo: AncoraDaLinha? = null
    private var distancia = 0

    fun antesDeVoltar() {
        noTopo = null
        antesDaVolta = lista.linhaNoTopo()
    }

    /** Chamada depois dos dois deslocamentos da volta. Sem C ou sem Q medidos, a volta fica como antes (sem limite). */
    fun depoisDeVoltar() {
        val c = antesDaVolta
        antesDaVolta = null
        val topoDeC = c?.let { lista.topoDaAncora(it) }
        val q = lista.linhaNoTopo()
        val d = if (topoDeC != null && q != null) distanciaDoLimite(topoDeC, q.topo) else null
        if (q != null && d != null) {
            noTopo = q
            distancia = d
        }
    }

    /**
     * Fundo da página com a volta em curso: a altura de C, enquanto Q é a linha do topo. Se a linha do topo mudou, o
     * limite é esquecido e vale [base].
     */
    fun limite(base: Int): Int {
        val q = noTopo ?: return base
        val atual = lista.linhaNoTopo()?.takeIf { it.posicao == q.posicao && it.linha == q.linha }
        if (atual == null) noTopo = null
        return if (atual == null) base else minOf(base, atual.topo + distancia)
    }
}

/**
 * Distância que a volta guarda entre C e Q (rb-67, item 1). Só vale se C ficou abaixo de Q: com zero, quando a linha
 * acima de C é maior que a página e Q é a própria C, o limite travaria a página em branco. Nula sem distância
 * positiva.
 */
internal fun distanciaDoLimite(topoDeC: Int, topoDeQ: Int): Int? = (topoDeC - topoDeQ).takeIf { it > 0 }

/**
 * A primeira linha de um parágrafo de leitura que começa no topo útil da lista (o `paddingTop`) ou depois. Nula sem
 * ela, por exemplo quando o topo cai numa linha de outra View. Função de topo: a conta é a mesma de [linhasNaTela].
 */
internal fun RecyclerViewParagrafos.linhaNoTopo(): AncoraDaLinha? =
    (0 until childCount)
        .mapNotNull { getChildViewHolder(getChildAt(it)) as? ParagrafoAdapter.VHLeitura }
        .filter { it.itemView === it.tv }
        .flatMap { ancorasDoParagrafo(it) }
        .filter { it.topo >= paddingTop }
        .minByOrNull { it.topo }

/** Topo atual da linha [a], se o parágrafo dela ainda está anexado como parágrafo de leitura. Nulo, se não está. */
internal fun RecyclerViewParagrafos.topoDaAncora(a: AncoraDaLinha): Int? {
    val holder = findViewHolderForAdapterPosition(a.posicao) as? ParagrafoAdapter.VHLeitura
    return holder?.takeIf { it.itemView === it.tv }?.let { ancorasDoParagrafo(it).getOrNull(a.linha)?.topo }
}

/** Âncoras das linhas de um parágrafo de leitura, nas coordenadas da lista. Vazio sem layout do texto. */
private fun RecyclerViewParagrafos.ancorasDoParagrafo(holder: ParagrafoAdapter.VHLeitura): List<AncoraDaLinha> {
    val tv = holder.tv
    val layout = tv.layout ?: return emptyList()
    val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
    return (0 until layout.lineCount).map {
        AncoraDaLinha(holder.bindingAdapterPosition, it, y0 + layout.getLineTop(it))
    }
}
