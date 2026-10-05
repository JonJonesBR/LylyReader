package com.jonjonesbr.audiobookgen.domain

/** Filtro de idioma da Central de downloads (e do download de vozes do primeiro acesso). */
enum class FiltroIdioma(val codigo: String?) { TODOS(null), PT("pt"), EN("en"), ES("es") }

object IdiomaDownload {
    /** Marca de pacote multilíngue: aparece em todos os filtros. */
    const val MULTILINGUE = "*"

    /** Normaliza rótulos de idioma das vozes ("pt-BR", "en-US", "multilingual"…) em códigos curtos ("pt", "en", "*"). */
    fun codigos(idiomasDasVozes: Collection<String>): Set<String> = idiomasDasVozes.mapNotNull { rotulo ->
        val limpo = rotulo.trim().lowercase()
        when {
            limpo.isEmpty() -> null
            limpo == "multilingual" || limpo == "multi" -> MULTILINGUE
            else -> limpo.substringBefore('-').substringBefore('_')
        }
    }.toSet()

    /** Um pacote sem idioma conhecido (ex.: voz importada) só aparece em "Todos". */
    fun combina(filtro: FiltroIdioma, idiomas: Set<String>): Boolean {
        val codigo = filtro.codigo ?: return true
        return MULTILINGUE in idiomas || codigo in idiomas
    }

    /** Filtro inicial sugerido pelo idioma do aparelho/app; qualquer outro idioma começa em "Todos". */
    fun padraoDoIdioma(idiomaDoApp: String): FiltroIdioma =
        FiltroIdioma.values().firstOrNull { it.codigo == idiomaDoApp.lowercase().substringBefore('-') } ?: FiltroIdioma.TODOS
}
