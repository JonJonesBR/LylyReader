package com.jonjonesbr.audiobookgen.data

/**
 * Cache em memória de uma lista lida de um texto (JSON nas preferências). Enquanto o texto não muda, devolve a mesma lista
 * sem parsear de novo: os Stores são consultados a cada parágrafo na leitura guiada.
 *
 * Não muda formato, chave nem arquivo. O [parse] precisa depender só do texto; se lançar exceção, nada é guardado (a
 * exceção segue para o Store, que mantém o próprio try/catch). A lista devolvida é compartilhada: não modificar.
 */
internal class CacheDeLista<T>(private val parse: (String) -> List<T>) {

    private class Entrada<T>(val raw: String, val lista: List<T>)

    // Texto e lista num único campo: uma leitura concorrente nunca vê o texto de uma entrada com a lista de outra.
    @Volatile
    private var atual: Entrada<T>? = null

    fun obter(raw: String): List<T> {
        val guardada = atual
        if (guardada != null && (raw === guardada.raw || raw == guardada.raw)) return guardada.lista
        val lista = parse(raw)
        atual = Entrada(raw, lista)
        return lista
    }
}
