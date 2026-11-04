package com.jonjonesbr.audiobookgen.ui

// Trecho guardado quando o parágrafo selecionado sai da tela (rb-58): sem nada de Android, testado na JVM.
// A pintura do span e a barra são do RecyclerViewParagrafos, do ParagrafoAdapter e do SelecaoSemMenu.

/**
 * Trecho que sai da tela junto com o parágrafo que o marcou. A barra da seleção continua e age sobre ele; quando o
 * parágrafo volta, o trecho aparece pintado. Não é a seleção nativa: ela não é restaurada, só o trecho.
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
