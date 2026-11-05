package com.jonjonesbr.audiobookgen.ui

// Regra pura da descida da lista quando a barra da seleção cobre o trecho marcado (rb-48): sem nada de Android,
// testada na JVM. Quem aplica o valor é o BarraDaSelecaoDeTexto, no translationY da RecyclerViewParagrafos.

/**
 * Deslocamento total (o translationY) que a lista precisa ter para a linha da seleção ficar abaixo da barra, com a
 * margem entre as duas. Quem chama soma ao espaço do menu flutuante da seleção (ver BarraDaSelecaoDeTexto).
 *
 * - [linha]: onde a linha do início da seleção está na tela agora, com o deslocamento atual já aplicado.
 * - [fundoDaBarraNaTela]: a base da barra da seleção, em coordenadas da tela.
 * - [alturaDaBarra]: a altura medida da barra; o limite do deslocamento depende dela (fonte grande, rb-48).
 * - [margemPx]: espaço entre a base da barra e a linha, em px (inclui o do menu flutuante).
 * - [deslocamentoAtual]: o translationY da lista agora. Negativo conta como 0.
 *
 * Devolve só o necessário, que pode ser menor que o atual (zero se a linha já está abaixo da barra). Quem decide
 * se a lista pode subir é quem chama: dentro de uma mesma seleção ela não sobe, para não pular ao trocar de
 * seleção, e uma seleção nova pode voltar ao lugar. O necessário fica entre 0 e a altura da barra, a margem e a
 * altura da linha, para uma linha acima da área visível não empurrar a lista para longe demais.
 */
internal fun quantoDescerParaMostrarSelecao(
    linha: LinhaNaTela,
    fundoDaBarraNaTela: Int,
    alturaDaBarra: Int,
    margemPx: Int,
    deslocamentoAtual: Int,
): Int {
    val atual = deslocamentoAtual.coerceAtLeast(0)
    // Topo da linha se a lista estivesse sem nenhum deslocamento.
    val topoSem = linha.topo - atual
    val limite = alturaDaBarra + margemPx + (linha.fundo - linha.topo)
    return (fundoDaBarraNaTela + margemPx - topoSem).coerceIn(0, limite)
}

/**
 * Se a seleção ficou parada desde o preparo anterior do menu (rb-50): mesmo início e mesmo fim. Quando a alça da
 * seleção é arrastada, cada passo muda a seleção, então o preparo chega com ela diferente. O preparo que vem depois
 * de soltar a alça (o do UP, síncrono ou assíncrono) repete a seleção, e é nele que a descida se refaz. O primeiro
 * preparo (sem anterior, com -1) não conta, e uma seleção vazia (início igual ao fim) também não.
 */
internal fun selecaoParadaNoPreparo(inicioAntes: Int, fimAntes: Int, inicio: Int, fim: Int): Boolean =
    inicio >= 0 && inicio != fim && inicio == inicioAntes && fim == fimAntes

/**
 * Se o preparo deve refazer a descida (rb-50): a seleção ficou parada desde o preparo anterior e é diferente da
 * seleção da última descida aplicada. O UP de uma rolagem que começa sobre o parágrafo selecionado também repete a
 * seleção, mas ela é a mesma da descida: refazer a descida ali faria a lista pular. [ultimaDescida] é a faixa
 * (início..fim) da seleção da última descida, ou nulo antes da primeira descida.
 */
internal fun refazerDescidaNoPreparo(
    inicioAntes: Int,
    fimAntes: Int,
    inicio: Int,
    fim: Int,
    ultimaDescida: IntRange?,
): Boolean =
    selecaoParadaNoPreparo(inicioAntes, fimAntes, inicio, fim) &&
        (ultimaDescida == null || inicio != ultimaDescida.first || fim != ultimaDescida.last)

/**
 * Onde a barra da seleção fica (rb-26b, passo 3): embaixo, no lugar do painel da guiada, no modo páginas; no topo, com
 * a descida, no vertical. [embaixo] escolhe a gravidade; [margemDeBaixo] é o bottomMargin (o mini player, quando
 * visível, fica abaixo da barra); [desce] diz se a lista pode descer para a linha da seleção (embaixo, não: o texto não
 * se mexe).
 */
internal data class LugarDaBarraDaSelecao(val embaixo: Boolean, val margemDeBaixo: Int, val desce: Boolean)

/**
 * Lugar da barra da seleção (rb-26b, passo 3). Com as páginas ativas, a barra fica embaixo e a margem é a altura que o
 * mini player ocupa acima da base da área ([fundoDaArea] − [topoDoMiniPlayer]), sem negativo. As duas coordenadas são
 * na janela.
 *
 * Não usa a altura útil (`height - paddingBottom`) de ReaderTelaController: lá a margem do FAB já inclui a barra de
 * navegação. Aqui a barra fica dentro da área, cuja base é a do mini player; descontar o padding deixaria a barra
 * cobrir `paddingBottom` px do mini player.
 */
internal fun lugarDaBarraDaSelecao(
    paginasAtivas: Boolean,
    fundoDaArea: Int,
    topoDoMiniPlayer: Int?,
): LugarDaBarraDaSelecao {
    if (!paginasAtivas) return LugarDaBarraDaSelecao(embaixo = false, margemDeBaixo = 0, desce = true)
    val margem = topoDoMiniPlayer?.let { (fundoDaArea - it).coerceAtLeast(0) } ?: 0
    return LugarDaBarraDaSelecao(embaixo = true, margemDeBaixo = margem, desce = false)
}
