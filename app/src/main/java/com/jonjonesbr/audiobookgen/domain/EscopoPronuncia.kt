package com.jonjonesbr.audiobookgen.domain

/** Onde uma pronúncia vale: só neste livro, ou em todos os livros (dicionário global). */
enum class EscopoPronuncia { LIVRO, TODOS }

/** Uma entrada do dicionário efetivo de um livro, com a origem dela. */
data class PronunciaComOrigem(val word: String, val spoken: String, val escopo: EscopoPronuncia)

object EscopoPronuncias {
    /**
     * Entradas efetivas do livro (global mais as do livro, que vencem em conflito de palavra), cada uma com a origem.
     * Ordenadas pela palavra, sem diferenciar caixa. Função pura.
     */
    fun listar(global: PronunciationDictionary, doLivro: PronunciationDictionary): List<PronunciaComOrigem> {
        val palavrasDoLivro = doLivro.entries.map { it.word.lowercase() }.toSet()
        val daObra = doLivro.entries.map { PronunciaComOrigem(it.word, it.spoken, EscopoPronuncia.LIVRO) }
        val daGlobal = global.entries
            .filterNot { it.word.lowercase() in palavrasDoLivro }
            .map { PronunciaComOrigem(it.word, it.spoken, EscopoPronuncia.TODOS) }
        return (daObra + daGlobal).sortedBy { it.word.lowercase() }
    }
}
