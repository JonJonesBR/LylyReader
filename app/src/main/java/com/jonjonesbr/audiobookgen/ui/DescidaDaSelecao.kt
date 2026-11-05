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
