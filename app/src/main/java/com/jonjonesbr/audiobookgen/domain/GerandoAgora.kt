package com.jonjonesbr.audiobookgen.domain

/**
 * O que o cartão "Gerando agora" das abas Biblioteca e Audiobooks mostra. Antes o andamento de uma conversão só
 * aparecia na tela Conversões, escondida no menu ⋮ da Biblioteca, e quem saía dela não achava mais o progresso.
 * A conversão rodando tem prioridade; depois a pausada (que espera o usuário retomar); por último a fila parada.
 */
sealed interface GerandoAgora {
    data class Gerando(val nome: String?, val pct: Int) : GerandoAgora
    data class Pausada(val nome: String, val pct: Int) : GerandoAgora
    data class NaFila(val quantidade: Int) : GerandoAgora
    object Nada : GerandoAgora

    companion object {
        fun de(
            rodando: Boolean,
            nomeRodando: String?,
            pctRodando: Int,
            pausada: Pair<String, Int>?,
            pendentesNaFila: Int
        ): GerandoAgora = when {
            rodando -> Gerando(nomeRodando?.takeIf { it.isNotBlank() }, pctRodando.coerceIn(0, PCT_MAX))
            pausada != null -> Pausada(pausada.first, pausada.second.coerceIn(0, PCT_MAX))
            pendentesNaFila > 0 -> NaFila(pendentesNaFila)
            else -> Nada
        }

        private const val PCT_MAX = 100
    }
}
