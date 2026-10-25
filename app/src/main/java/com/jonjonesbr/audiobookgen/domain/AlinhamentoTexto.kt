package com.jonjonesbr.audiobookgen.domain

/** Alinhamento do texto no leitor. Justificado é o padrão (como num livro impresso). Persistido pelo nome. */
enum class AlinhamentoTexto {
    JUSTIFICADO, ESQUERDA, CENTRO, DIREITA;

    companion object {
        /** Lê o nome gravado; valor ausente ou desconhecido volta ao padrão, [JUSTIFICADO]. */
        fun porNome(nome: String?): AlinhamentoTexto = entries.firstOrNull { it.name == nome } ?: JUSTIFICADO
    }
}
