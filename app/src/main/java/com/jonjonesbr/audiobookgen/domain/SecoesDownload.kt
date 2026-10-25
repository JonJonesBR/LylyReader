package com.jonjonesbr.audiobookgen.domain

/**
 * Seções da aba Downloads, na ordem em que aparecem: o que está baixando primeiro, depois o que já está no
 * aparelho, o que dá para baixar, as vozes do próprio usuário e os livros. Antes eram grupos técnicos
 * ("Vozes e motores", "Codificadores") com seleção por caixas e um rodapé fixo de botões.
 */
enum class SecaoDownload { BAIXANDO, NO_APARELHO, PARA_BAIXAR, MINHAS_VOZES, LIVROS }

/** Tipo de pacote, para a linha de descrição ("Vozes naturais · PT, EN, ES"). */
enum class TipoPacote { SUPERTONIC, KOKORO, PIPER, POCKET, MMS, CLONADA, IMPORTADA, LIVRO, OUTRO }

object SecoesDownload {

    private val SUFIXO_TECNICO = Regex("""\s*\((?:Sherpa-)?ONNX local\)\s*$""")

    /** Seção de um item: baixando (de qualquer tipo) vem antes de tudo; voz do usuário e livro têm a sua. */
    fun secao(ehLivro: Boolean, ehDoUsuario: Boolean, estado: EstadoDownload): SecaoDownload = when {
        estado == EstadoDownload.NA_FILA || estado == EstadoDownload.BAIXANDO || estado == EstadoDownload.PAUSADO ->
            SecaoDownload.BAIXANDO
        ehLivro -> SecaoDownload.LIVROS
        ehDoUsuario -> SecaoDownload.MINHAS_VOZES
        estado == EstadoDownload.PRONTO -> SecaoDownload.NO_APARELHO
        else -> SecaoDownload.PARA_BAIXAR
    }

    /** "Supertonic (ONNX local)" → "Supertonic"; "Kokoro (Sherpa-ONNX local)" → "Kokoro". */
    fun nomeAmigavel(nome: String): String = nome.replace(SUFIXO_TECNICO, "").ifBlank { nome }

    fun tipo(id: String, ehLivro: Boolean, ehDoUsuario: Boolean): TipoPacote = when {
        ehLivro -> TipoPacote.LIVRO
        id.startsWith("pocket-custom-") -> TipoPacote.CLONADA
        ehDoUsuario -> TipoPacote.IMPORTADA
        id.startsWith("supertonic") -> TipoPacote.SUPERTONIC
        id == "kokoro" -> TipoPacote.KOKORO
        id.startsWith("piper-") -> TipoPacote.PIPER
        id.startsWith("pocket-") -> TipoPacote.POCKET
        id.startsWith("mms-") -> TipoPacote.MMS
        else -> TipoPacote.OUTRO
    }

    /**
     * "pt", "en", "es" → "PT, EN, ES", na ordem fixa pt, en, es e depois o resto em ordem alfabética.
     * O marcador de pacote multilíngue ([IdiomaDownload.MULTILINGUE]) fica de fora: a tela diz "vários idiomas".
     */
    fun idiomasCurtos(codigos: Set<String>): String {
        val ordem = listOf("pt", "en", "es")
        val resto = (codigos - ordem.toSet() - IdiomaDownload.MULTILINGUE).sorted()
        return (ordem.filter { it in codigos } + resto).joinToString(", ") { it.uppercase() }
    }
}
