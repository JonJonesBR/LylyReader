package com.jonjonesbr.audiobookgen.ui

/**
 * Opções escolhidas no painel "Gerar audiobook" da tela do livro, valendo SÓ para a conversão que ela inicia (não
 * mudam as preferências globais). Campo nulo = usa o perfil do livro e, na falta dele, o que há nas preferências.
 */
data class OpcoesConversao(
    val voz: String? = null,
    val motor: String? = null,
    val ritmo: Int? = null,
    /** Estilo de narração (só tem efeito com Gemini); nulo = padrão. */
    val estilo: String? = null
)
