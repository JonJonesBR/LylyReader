package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura da explicação antes do pedido de bateria (sem Android).
 * Antes de abrir a tela do Android, o app diz o que ela vai mostrar: a pergunta direta, quando o app ainda
 * tem restrição, ou a lista geral, quando ele já aparece nela. Em Xiaomi, Redmi e POCO, a economia de bateria
 * do próprio sistema também precisa ser liberada, e a tela padrão não cobre isso.
 */
object ExplicacaoBateria {

    /** Qual tela o pedido abre: a pergunta "Permitir" do Android ou a lista de otimização de bateria. */
    enum class Tela { PERGUNTA, LISTA }

    /** Valores de `Build.MANUFACTURER` (sem diferenciar maiúsculas) com economia de bateria própria. */
    private val fabricantesComEconomiaPropria = setOf("xiaomi", "redmi", "poco")

    /** Com restrição ainda ativa, a tela é a pergunta direta; já liberado, a lista geral. */
    fun telaDoPedido(otimizacaoIgnorada: Boolean): Tela =
        if (otimizacaoIgnorada) Tela.LISTA else Tela.PERGUNTA

    fun ehXiaomi(fabricante: String?): Boolean =
        fabricante.orEmpty().trim().lowercase() in fabricantesComEconomiaPropria
}
