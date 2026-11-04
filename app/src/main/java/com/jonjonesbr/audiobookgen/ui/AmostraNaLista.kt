package com.jonjonesbr.audiobookgen.ui

/**
 * Fase da amostra de voz numa linha da lista (rb-29): ▶ parada, círculo girando (gerando) ou ■ (tocando).
 * Público porque aparece em assinaturas públicas ([VoiceBottomSheet.configure], [AudiobookViewModel]).
 */
enum class FaseDaAmostra { PARADA, GERANDO, TOCANDO }

/**
 * Fase de um pedido de amostra, lida no momento. Pedido diferente do atual é PARADA: uma amostra substituída
 * não acende nada. Gerando vence tocando, porque a prévia começa antes de soltar "gerando" (rb-24).
 */
internal fun faseDoPedido(pedido: Int, pedidoAtual: Int, gerando: Boolean, tocando: Boolean): FaseDaAmostra = when {
    pedido != pedidoAtual -> FaseDaAmostra.PARADA
    gerando -> FaseDaAmostra.GERANDO
    tocando -> FaseDaAmostra.TOCANDO
    else -> FaseDaAmostra.PARADA
}

/**
 * Estado da lista de vozes: só uma voz tem amostra por vez (a prévia é única). Fica no [VoiceBottomSheet]; quem
 * inicia uma voz é só o clique no próprio sheet, então um aviso atrasado nunca acende uma linha.
 */
internal data class AmostraNaLista(val vozId: String? = null, val fase: FaseDaAmostra = FaseDaAmostra.PARADA) {

    fun faseDa(id: String): FaseDaAmostra = if (id == vozId) fase else FaseDaAmostra.PARADA

    /** ▶ numa voz parada começa a amostra dela e substitui a anterior; em qualquer outro caso para (cancela). */
    fun aoTocar(id: String): AmostraNaLista =
        if (faseDa(id) == FaseDaAmostra.PARADA) AmostraNaLista(id, FaseDaAmostra.GERANDO) else AmostraNaLista()

    /** Aviso da prévia: ignora vozes que não são a ativa; PARADA limpa a linha (fim natural, ■ ou falha). */
    fun aoReceber(id: String, fase: FaseDaAmostra): AmostraNaLista = when {
        id != vozId -> this
        fase == FaseDaAmostra.PARADA -> AmostraNaLista()
        else -> copy(fase = fase)
    }
}
