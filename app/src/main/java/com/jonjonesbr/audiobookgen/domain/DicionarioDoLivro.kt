package com.jonjonesbr.audiobookgen.domain

/**
 * Dicionário efetivo de um livro: o global mais o do próprio livro (o do livro vence em conflito).
 * É o mesmo cálculo na leitura guiada e na conversão, para que o livro seja falado igual nas duas.
 * Função pura: sem livro próprio, devolve o global sem refazer nada.
 */
object DicionarioDoLivro {

    /** Última combinação calculada. Guardada num objeto só, para a leitura ser consistente entre threads. */
    private class Memo(
        val global: PronunciationDictionary,
        val doLivro: PronunciationDictionary,
        val resultado: PronunciationDictionary
    )

    @Volatile private var memo: Memo? = null

    /**
     * Quando [global] e [doLivro] são os mesmos objetos da chamada anterior, devolve o mesmo resultado (sem refazer
     * a mesclagem nem a regex). A síntese chama isto a cada trecho, então isso evita trabalho repetido.
     */
    fun efetivo(global: PronunciationDictionary, doLivro: PronunciationDictionary): PronunciationDictionary {
        if (doLivro.isEmpty()) return global
        val anterior = memo
        if (anterior != null && anterior.global === global && anterior.doLivro === doLivro) return anterior.resultado
        val resultado = PronunciationDictionary.mesclar(global, doLivro)
        memo = Memo(global, doLivro, resultado)
        return resultado
    }
}
