package com.jonjonesbr.audiobookgen.domain

/**
 * Alinhamento do texto no leitor. Persistido pelo nome. O padrão é à esquerda: no justificado o Android posiciona
 * a seleção de texto sem contar o espaço esticado entre as palavras, e as alças não ficam onde o dedo solta.
 */
enum class AlinhamentoTexto {
    JUSTIFICADO, ESQUERDA, CENTRO, DIREITA;

    companion object {
        /** Lê o nome gravado; valor ausente ou desconhecido volta ao padrão, [ESQUERDA]. */
        fun porNome(nome: String?): AlinhamentoTexto = entries.firstOrNull { it.name == nome } ?: ESQUERDA
    }
}
