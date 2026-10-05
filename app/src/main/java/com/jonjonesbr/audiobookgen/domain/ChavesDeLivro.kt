package com.jonjonesbr.audiobookgen.domain

/**
 * Regras puras para levar os dados de um livro de um caminho de arquivo para outro (migração para a biblioteca).
 * As lojas (SharedPreferences, JSON) só aplicam o resultado.
 */
object ChavesDeLivro {

    /**
     * Mapa chave→valor onde a chave é `prefixo + caminho`. Copia o valor de `prefixo+antigo` para `prefixo+novo`.
     * Se `prefixo+novo` já existe, [resolver] decide (padrão: **mantém o existente** — nunca sobrescreve).
     * Não remove a chave antiga. Sem a chave antiga (ou caminhos iguais) devolve o mapa como veio.
     */
    fun migrarMapa(
        mapa: Map<String, Any?>,
        antigo: String,
        novo: String,
        prefixo: String = "",
        resolver: (valorAntigo: Any?, valorExistente: Any?) -> Any? = { _, existente -> existente }
    ): Map<String, Any?> {
        if (antigo == novo) return mapa
        val chaveAntiga = prefixo + antigo
        if (chaveAntiga !in mapa) return mapa
        val chaveNova = prefixo + novo
        val valorAntigo = mapa[chaveAntiga]
        val resultado = if (chaveNova in mapa) resolver(valorAntigo, mapa[chaveNova]) else valorAntigo
        return mapa + (chaveNova to resultado)
    }

    /** Para posições de leitura: vale a mais adiantada (o maior inteiro). */
    val maiorInteiro: (Any?, Any?) -> Any? = { antigo, existente ->
        val a = antigo as? Int
        val e = existente as? Int
        if (a != null && e != null) maxOf(a, e) else existente ?: antigo
    }

    /** Troca o caminho do livro nos itens que apontam para [antigo]; os demais ficam como estão. */
    fun <T> migrarLista(lista: List<T>, antigo: String, novo: String, caminho: (T) -> String, trocar: (T, String) -> T): List<T> =
        if (antigo == novo) lista else lista.map { if (caminho(it) == antigo) trocar(it, novo) else it }

    /** Lista de caminhos (ex.: livros concluídos): troca [antigo] por [novo] sem repetir. */
    fun migrarCaminhos(caminhos: List<String>, antigo: String, novo: String): List<String> =
        if (antigo == novo || antigo !in caminhos) caminhos else caminhos.map { if (it == antigo) novo else it }.distinct()
}
