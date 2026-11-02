package com.jonjonesbr.audiobookgen.domain

import java.util.Locale

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

/**
 * Linha de detalhe de um livro na lista da busca: autor · idioma · ano · "já baixado". O idioma só entra com o
 * filtro "Todos" (com um idioma escolhido, ele já é o filtro) e sai no nome do idioma do app.
 */
fun linhaDetalheLivro(
    livro: LivroEncontrado,
    mostrarIdioma: Boolean,
    jaBaixado: Boolean,
    rotuloJaBaixado: String,
    idiomaDoApp: Locale
): String = listOfNotNull(
    livro.autor.trim().ifBlank { null },
    livro.idioma?.trim()?.ifBlank { null }?.takeIf { mostrarIdioma }?.let { nomeDoIdioma(it, idiomaDoApp) },
    livro.ano?.trim()?.ifBlank { null },
    rotuloJaBaixado.takeIf { jaBaixado }
).joinToString(" · ")

/** Códigos e nomes de idioma que as fontes mandam (ex.: "pt", "por", "Portuguese", "PT") → código curto. */
private val codigosDeIdioma: Map<String, String> = buildMap {
    fun alias(codigo: String, vararg outros: String) {
        put(codigo, codigo)
        outros.forEach { put(it, codigo) }
    }
    alias("pt", "por", "portuguese", "português", "portugues")
    alias("en", "eng", "english")
    alias("es", "spa", "spanish", "español", "espanol")
    alias("fr", "fra", "fre", "french", "français")
    alias("de", "deu", "ger", "german", "deutsch")
    alias("it", "ita", "italian", "italiano")
    alias("la", "lat", "latin")
}

/** Nome do idioma no idioma do app, com a primeira letra maiúscula. Se não reconhece, devolve como veio. */
fun nomeDoIdioma(rotulo: String, idiomaDoApp: Locale): String {
    val codigo = codigosDeIdioma[rotulo.trim().lowercase(Locale.ROOT)] ?: return rotulo
    return Locale.forLanguageTag(codigo).getDisplayLanguage(idiomaDoApp)
        .ifBlank { return rotulo }
        .replaceFirstChar { it.titlecase(idiomaDoApp) }
}
