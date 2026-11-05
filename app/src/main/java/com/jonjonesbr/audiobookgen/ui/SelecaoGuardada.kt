package com.jonjonesbr.audiobookgen.ui

// Trecho guardado quando o parágrafo selecionado sai da tela (rb-58): sem nada de Android, testado na JVM.
// A pintura do span e a barra são do RecyclerViewParagrafos, do ParagrafoAdapter e do SelecaoSemMenu.

/**
 * Trecho que sai da tela junto com o parágrafo que o marcou. A barra da seleção continua e age sobre ele; quando o
 * parágrafo volta, o trecho aparece pintado. Não é a seleção nativa: ela volta pelo toque dentro do trecho (rb-60) ou
 * sozinha, quando a lista para com o trecho inteiro na área útil (rb-60b, ver [reabrirSozinho]).
 *
 * Só um trecho por vez. Quem limpa é quem chama: Voltar, toque curto, ação da barra, seleção nova ou lista nova.
 */
internal class SelecaoGuardada {
    /** Posição do parágrafo do trecho. [SEM_TRECHO] quando não há trecho guardado. */
    var posicao: Int = SEM_TRECHO
        private set

    /** Início do trecho no parágrafo (offset de caractere, fim exclusivo). */
    var inicio: Int = 0
        private set

    /** Fim do trecho no parágrafo (exclusivo). */
    var fim: Int = 0
        private set

    /** Texto do trecho, sem espaços nas pontas: é o que as ações da barra recebem. */
    var texto: String = ""
        private set

    /** Texto inteiro do parágrafo quando o trecho foi guardado. Se o parágrafo mudou, o trecho não vale mais. */
    private var textoDoParagrafo: String = ""

    /** Se há um trecho guardado agora. */
    val ativa: Boolean get() = posicao != SEM_TRECHO

    /** Guarda [trecho] do parágrafo [posicao], cujo texto inteiro é [textoDoParagrafo]. Troca o que havia. */
    fun guardar(posicao: Int, trecho: TrechoSelecionado, textoDoParagrafo: String) {
        this.posicao = posicao
        inicio = trecho.inicio
        fim = trecho.fim
        texto = trecho.texto
        this.textoDoParagrafo = textoDoParagrafo
    }

    /** O trecho como [TrechoSelecionado] (para as ações da barra), ou nulo sem trecho guardado. */
    fun trecho(): TrechoSelecionado? = if (ativa) TrechoSelecionado(inicio, fim, texto) else null

    /** Esquece o trecho. */
    fun limpar() {
        posicao = SEM_TRECHO
        inicio = 0
        fim = 0
        texto = ""
        textoDoParagrafo = ""
    }

    /**
     * Faixa do trecho dentro do parágrafo [posicao] com o texto [textoAtual], para pintar. Nulo se não há trecho, se a
     * posição é outra ou se o texto mudou desde que o trecho foi guardado.
     */
    fun trechoNoParagrafo(posicao: Int, textoAtual: String): IntRange? {
        val mesmoParagrafo = ativa && posicao == this.posicao && textoAtual == textoDoParagrafo
        val faixaValida = inicio >= 0 && fim > inicio && fim <= textoAtual.length
        return if (mesmoParagrafo && faixaValida) inicio until fim else null
    }

    companion object {
        const val SEM_TRECHO = -1
    }
}

/**
 * Se o parágrafo com a seleção deve guardar o trecho ao ser desanexado (rb-58). Só o parágrafo de leitura conta (na
 * edição e na comparação a seleção é do menu do sistema), com o foco e com trecho marcado.
 */
internal fun guardarAoSairDaTela(modoLeitura: Boolean, comFoco: Boolean, temTrecho: Boolean): Boolean =
    modoLeitura && comFoco && temTrecho

/**
 * Se um toque curto que caiu no offset [offsetTocado] do parágrafo [posicaoDoItem] reabre o trecho guardado (rb-60).
 * Vale só com o trecho ainda guardado no mesmo parágrafo e com o mesmo texto, com a lista e a página parada no
 * DOWN (o toque que para um fling é da lista ou da página, não do texto) e com o offset dentro do trecho. O fim
 * conta: o `getOffsetForPosition` devolve a fronteira mais próxima, então um toque na metade direita da última letra
 * cai no fim do trecho.
 * Devolve o trecho, ou nulo.
 */
internal fun toqueReabreTrecho(
    posicaoDoItem: Int,
    offsetTocado: Int,
    listaParadaNoDown: Boolean,
    guardada: SelecaoGuardada,
    textoAtual: String,
): IntRange? {
    val trecho = guardada.trechoNoParagrafo(posicaoDoItem, textoAtual) ?: return null
    // Fronteiras do trecho, inclusive a final (ver o KDoc).
    return trecho.takeIf { listaParadaNoDown && offsetTocado in it.first..it.last + 1 }
}

/**
 * Se a seleção nativa do trecho guardado volta sozinha (rb-60b): a lista parou (IDLE e página parada), sem dedo na
 * tela, sem outro texto em uso, sem a guiada conduzindo a rolagem e com o trecho inteiro na área útil. Cada condição
 * é um bloqueio: basta uma falsa para o trecho ficar pintado, como antes, à espera do toque.
 */
internal fun reabrirSozinho(
    rolagemParada: Boolean,
    dedoNaTela: Boolean,
    outroTextoEmUso: Boolean,
    guiadaConduzRolagem: Boolean,
    trechoInteiroVisivel: Boolean,
): Boolean = rolagemParada && !dedoNaTela && !outroTextoEmUso && !guiadaConduzRolagem && trechoInteiroVisivel

/**
 * Área útil onde o trecho guardado pode ficar para voltar sozinho (rb-60b), em coordenadas da tela. O topo é o da
 * lista, ou o limite da barra da seleção ([limiteDaBarra], nulo sem barra ou com a barra embaixo) se ela estiver mais
 * abaixo. O fundo é o menor entre a lista, o corte do pai (que já inclui a descida), o topo da guiada ([topoDaGuiada],
 * nulo sem guiada) e o topo da barra quando ela está embaixo ([topoDaBarraEmbaixo], rb-26b passo 3, nulo no topo).
 * Com a barra embaixo, o trecho não pode ficar sob ela; o topo da guiada (INVISIBLE, cedida) já sai da conta.
 */
internal fun areaUtilParaReabrir(
    areaDaLista: LinhaNaTela,
    fundoDoPai: Int,
    limiteDaBarra: Int?,
    topoDaGuiada: Int?,
    topoDaBarraEmbaixo: Int? = null,
): LinhaNaTela = LinhaNaTela(
    topo = maxOf(areaDaLista.topo, limiteDaBarra ?: Int.MIN_VALUE),
    fundo = minOf(areaDaLista.fundo, fundoDoPai, topoDaGuiada ?: Int.MAX_VALUE, topoDaBarraEmbaixo ?: Int.MAX_VALUE),
)

/**
 * Se [trecho] cabe inteiro dentro de [area] (rb-60b). As bordas iguais contam: é o caso do trecho na posição exata da
 * descida. Área vazia ou invertida não cabe nada.
 */
internal fun trechoInteiroNaArea(trecho: LinhaNaTela, area: LinhaNaTela): Boolean =
    area.topo < area.fundo && trecho.topo >= area.topo && trecho.fundo <= area.fundo
