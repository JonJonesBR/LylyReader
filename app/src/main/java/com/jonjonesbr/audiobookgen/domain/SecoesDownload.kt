package com.jonjonesbr.audiobookgen.domain

/**
 * Seções da aba Downloads, na ordem em que aparecem: o que está baixando primeiro, depois o que já está no
 * aparelho, o que dá para baixar, as vozes do próprio usuário e os livros. Antes eram grupos técnicos
 * ("Vozes e motores", "Codificadores") com seleção por caixas e um rodapé fixo de botões.
 */
enum class SecaoDownload { BAIXANDO, NO_APARELHO, PARA_BAIXAR, MINHAS_VOZES, LIVROS }

/** Tipo de pacote, para a linha de descrição ("Vozes naturais · PT, EN, ES"). */
enum class TipoPacote { SUPERTONIC, KOKORO, PIPER, POCKET, MMS, CLONADA, IMPORTADA, LIVRO, OUTRO }

/** Linha 1 (nome) e linha 2 (motor, descrição, idiomas e tamanho) de uma voz na aba Downloads. */
data class LinhasVoz(val nome: String, val descricao: String)

object SecoesDownload {

    private val SUFIXO_TECNICO = Regex("""\s*\((?:Sherpa-)?ONNX local\)\s*$""")
    private val IDIOMA_ENTRE_PARENTESES = Regex("""(.*?)\s*\(([^()]+)\)""")
    private val CODIGO_DE_IDIOMA = Regex("""[a-zA-Z]{2,3}[-_][a-zA-Z]{2,4}""")
    /** Nomes de idioma que aparecem nos nomes do catálogo ("Pocket TTS · Português do Brasil"). */
    private val NOMES_DE_IDIOMA = setOf("português", "português do brasil", "inglês", "english", "español", "espanhol")

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

    /** Nome do motor na linha 2 ("Piper · leve e rápida"). Null quando não há motor: voz do usuário, livro, outro. */
    fun motor(tipo: TipoPacote): String? = when (tipo) {
        TipoPacote.SUPERTONIC -> "Supertonic"
        TipoPacote.KOKORO -> "Kokoro"
        TipoPacote.PIPER -> "Piper"
        TipoPacote.POCKET -> "Pocket TTS"
        TipoPacote.MMS -> "MMS-TTS"
        TipoPacote.CLONADA, TipoPacote.IMPORTADA, TipoPacote.LIVRO, TipoPacote.OUTRO -> null
    }

    /**
     * Linhas de uma voz. Com motor, o nome perde o próprio motor ("Jeff · Piper · pt-BR" → "Jeff") e, quando o
     * idioma já está na linha 2, o idioma também ("Supertonic (English)" → "Supertonic"). Se não sobra nada,
     * fica o primeiro pedaço ("Pocket TTS · Português do Brasil" → "Pocket TTS"). Sem o idioma na linha 2 (filtro
     * de idioma ligado), ele fica no nome, para separar vozes de idiomas diferentes ("Supertonic (English)").
     * Sem motor, o nome sai como veio. A linha 2 junta motor (ou a descrição, na voz do usuário), descrição,
     * idiomas e tamanho, deixando de fora o que vier nulo ou vazio: [idiomas] é nulo quando o filtro de idioma
     * não é "Todos" (ou a voz não tem idioma a mostrar) e [tamanho] só vem na voz pronta.
     */
    fun linhasVoz(
        nomeCompleto: String,
        motor: String?,
        descricao: String?,
        idiomas: String?,
        tamanho: String?
    ): LinhasVoz {
        val idiomaNaLinha2 = !idiomas.isNullOrBlank()
        val nome = if (motor == null) nomeCompleto else nomeSemMotorEIdioma(nomeCompleto, motor, idiomaNaLinha2)
        val linha2 = listOfNotNull(motor, descricao, idiomas, tamanho).filter { it.isNotBlank() }.joinToString(" · ")
        return LinhasVoz(nome, linha2)
    }

    private fun nomeSemMotorEIdioma(nome: String, motor: String, idiomaNaLinha2: Boolean): String {
        val semIdiomaEntreParenteses = if (idiomaNaLinha2) tirarIdiomaEntreParenteses(nome) else nome
        val pedacos = semIdiomaEntreParenteses.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val sobra = pedacos.filterNot { it.equals(motor, ignoreCase = true) || (idiomaNaLinha2 && ehIdioma(it)) }
        return if (sobra.isNotEmpty()) sobra.joinToString(" · ") else pedacos.firstOrNull() ?: nome
    }

    /** "Supertonic (English)" → "Supertonic"; sem idioma entre parênteses, o nome sai igual. */
    private fun tirarIdiomaEntreParenteses(nome: String): String =
        IDIOMA_ENTRE_PARENTESES.matchEntire(nome)
            ?.takeIf { ehIdioma(it.groupValues[2].trim()) }
            ?.groupValues?.get(1) ?: nome

    private fun ehIdioma(pedaco: String): Boolean =
        pedaco.lowercase() in NOMES_DE_IDIOMA || CODIGO_DE_IDIOMA.matches(pedaco)
}
