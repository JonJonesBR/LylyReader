package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo

/** Velocidade média de leitura silenciosa usada para estimar tempo restante. */
const val PALAVRAS_POR_MINUTO_PADRAO = 200

/** % do livro já lido (0f..1f), a partir do charOffset do parágrafo em foco. */
fun progressoLivroPct(paragrafos: List<Paragrafo>, indiceAtual: Int, totalChars: Int): Float {
    val offset = paragrafos.getOrNull(indiceAtual)?.charOffset
    if (totalChars <= 0 || offset == null) return 0f
    return (offset.toFloat() / totalChars).coerceIn(0f, 1f)
}

/**
 * Minutos restantes de leitura no capítulo atual, contando a partir do PRÓXIMO parágrafo
 * (o parágrafo em [indiceAtual] é considerado já em leitura, não pendente).
 */
fun minutosRestantesCapitulo(
    paragrafos: List<Paragrafo>,
    indiceAtual: Int,
    capitulos: List<Capitulo>,
    palavrasPorMinuto: Int = PALAVRAS_POR_MINUTO_PADRAO
): Int {
    val capitulo = capitulos.firstOrNull { indiceAtual in it.indiceParagrafoInicio..it.indiceParagrafoFim }
    if (palavrasPorMinuto <= 0 || capitulo == null) return 0
    val palavras = ((indiceAtual + 1)..capitulo.indiceParagrafoFim).sumOf { i ->
        paragrafos.getOrNull(i)?.texto
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.count { it.isNotEmpty() }
            ?: 0
    }
    return kotlin.math.ceil(palavras.toFloat() / palavrasPorMinuto).toInt()
}
