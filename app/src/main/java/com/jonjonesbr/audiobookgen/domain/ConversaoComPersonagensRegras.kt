package com.jonjonesbr.audiobookgen.domain

/**
 * Decide se a conversão pela fila/biblioteca deve usar o texto reconstruído do mapa de personagens em vez do arquivo original.
 *
 * Pelo arquivo, epub/txt/md ganham marcas de capítulo da própria extração; pelo mapa, só vêm os capítulos que o mapa guardou.
 * Então, para esses formatos, o mapa só entra quando ele tem capítulos (senão a conversão perderia as marcas).
 */
object ConversaoComPersonagensRegras {

    private val FORMATOS_COM_CAPITULOS_NA_EXTRACAO = setOf("epub", "txt", "md")

    /** Diz se a extração do arquivo (pela conversão direta) gera capítulos para este formato. */
    fun formatoTemCapitulosNaExtracao(extensaoDoLivro: String): Boolean =
        extensaoDoLivro.trim().lowercase() in FORMATOS_COM_CAPITULOS_NA_EXTRACAO

    fun usarMapa(temVozes: Boolean, temTextoDoMapa: Boolean, mapaTemCapitulos: Boolean, extensaoDoLivro: String): Boolean {
        if (!temVozes || !temTextoDoMapa) return false
        return mapaTemCapitulos || !formatoTemCapitulosNaExtracao(extensaoDoLivro)
    }
}
