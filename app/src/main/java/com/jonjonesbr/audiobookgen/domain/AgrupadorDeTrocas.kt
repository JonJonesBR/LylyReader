package com.jonjonesbr.audiobookgen.domain

/** Uma troca feita pela normalização: como o texto está (`antes`) e como será falado (`depois`). */
data class TrocaNormalizador(val antes: String, val depois: String)

/** Trocas iguais reunidas: quantas vezes o mesmo par `antes → depois` aparece no texto. */
data class TrocaAgrupada(val antes: String, val depois: String, val contagem: Int)

/** Agrupa o relatório de trocas para exibir sem repetição (função pura). */
object AgrupadorDeTrocas {

    /**
     * Deduplica pares iguais, conta as ocorrências, ordena por contagem (maior primeiro) e depois
     * alfabeticamente pelo `antes` e pelo `depois`, e corta no `limite`. Limite menor ou igual a zero
     * devolve lista vazia.
     */
    fun agrupar(trocas: List<TrocaNormalizador>, limite: Int): List<TrocaAgrupada> {
        if (limite <= 0) return emptyList()
        return trocas
            .groupingBy { it.antes to it.depois }
            .eachCount()
            .map { (par, contagem) -> TrocaAgrupada(par.first, par.second, contagem) }
            .sortedWith(
                Comparator { a, b ->
                    when {
                        a.contagem != b.contagem -> b.contagem.compareTo(a.contagem)
                        a.antes != b.antes -> a.antes.compareTo(b.antes, ignoreCase = true)
                        else -> a.depois.compareTo(b.depois, ignoreCase = true)
                    }
                }
            )
            .take(limite)
    }
}

/** Linha da lista de conferência: "antes → depois (×N)" (função pura). */
fun formatarLinhaTroca(troca: TrocaAgrupada): String =
    "${troca.antes} → ${troca.depois} (×${troca.contagem})"
