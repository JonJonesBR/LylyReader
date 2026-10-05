package com.jonjonesbr.audiobookgen.domain

/** Fontes legais de livros (domínio público / licença livre) buscáveis dentro do app. */
enum class FonteLivros { GUTENBERG, WIKISOURCE, ARCHIVE }

/** Filtros de idioma da busca (null = todos; o Wikisource precisa de um idioma específico). */
enum class IdiomaBusca(val codigo: String?) { TODOS(null), PT("pt"), EN("en"), ES("es") }

/** Um livro/obra encontrado numa fonte. [urlEpub] é o EPUB; no Internet Archive é o `metadata` do item (o arquivo é escolhido ao baixar). */
data class LivroEncontrado(
    /** Identificador estável dentro da fonte (vira nome de pasta no cache). */
    val chave: String,
    val titulo: String,
    val autor: String,
    val idioma: String?,
    val urlEpub: String,
    /** Ano de publicação, quando a fonte informa. */
    val ano: String? = null,
    /** Miniatura da capa, quando a fonte oferece (o Wikisource não). */
    val capaUrl: String? = null
) {
    /** Nome do arquivo = título legível (é o que o app mostra como nome do livro). */
    val nomeArquivo: String get() = nomeSeguro(titulo) + ".epub"

    private fun nomeSeguro(texto: String): String =
        texto.replace(Regex("""[\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(NOME_MAX).trim().ifEmpty { "livro" }

    private companion object {
        const val NOME_MAX = 80
    }
}

/** Uma página de resultados; [proximoInicio] = posição para pedir a página seguinte (null = acabou). */
data class ResultadoBusca(val livros: List<LivroEncontrado>, val proximoInicio: Int?)
