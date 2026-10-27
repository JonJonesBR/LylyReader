package com.jonjonesbr.audiobookgen.util

/**
 * Resolve a posição de PARÁGRAFO (índice real no RecyclerView) do resultado de busca atual.
 *
 * [resultados] é a lista de parágrafos que contêm o termo buscado (cada entrada já é uma
 * posição de parágrafo); [indiceAtual] é a posição DENTRO dessa lista — "este é o resultado
 * nº 2 de 5" — não a posição do parágrafo em si. Confundir os dois foi um bug real: tanto o
 * highlight do resultado atual quanto o scroll-to comparavam/usavam [indiceAtual] diretamente
 * como se já fosse a posição do parágrafo, fazendo a busca "pular" sempre para os primeiros
 * parágrafos do livro (posição 0, 1, 2…) em vez do parágrafo onde o termo realmente aparece.
 */
fun posicaoResultadoBusca(resultados: List<Int>, indiceAtual: Int): Int? =
    resultados.getOrNull(indiceAtual)

/**
 * Texto preparado para a busca: [normal] é a versão "achatada" (minúsculas, sem acentos, aspas e
 * apóstrofos unificados, espaços repetidos viram um só) e [origem] diz, para cada caractere de
 * [normal], de que posição do texto original ele veio — para destacar o trecho exato achado.
 */
class TextoBuscavel(val normal: String, val origem: IntArray)

private const val LIMITE_ASCII = 128

// Alguns caracteres viram mais de um ao normalizar ("ﬁ" -> "fi"); o vetor cresce se precisar.
private const val FOLGA_EXPANSAO = 16

private fun Char.ehMarcaCombinante(): Boolean =
    Character.getType(this) == Character.NON_SPACING_MARK.toInt()

/** Versão de um caractere para comparação: "Ç" -> "c", "É" -> "e", "’" -> "'", "“" -> "\"". */
private fun normalizarCaractere(c: Char): String = when (c) {
    '‘', '’', '‚', '‛', '´', '`' -> "'"
    '“', '”', '„', '«', '»' -> "\""
    '‐', '‑', '‒', '–', '—', '―' -> "-"
    else -> if (c.code < LIMITE_ASCII) {
        c.lowercaseChar().toString()
    } else {
        java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKD)
            .filterNot { it.ehMarcaCombinante() }
            .lowercase()
    }
}

/** Prepara [texto] para a busca (ver [TextoBuscavel]). */
fun prepararParaBusca(texto: String): TextoBuscavel {
    val normal = StringBuilder(texto.length)
    var origem = IntArray(texto.length + FOLGA_EXPANSAO)
    var n = 0
    var ultimoFoiEspaco = false
    for (i in texto.indices) {
        val c = texto[i]
        if (c.isWhitespace() || c == ' ') {
            if (!ultimoFoiEspaco && n > 0) {
                if (n == origem.size) origem = origem.copyOf(n * 2)
                normal.append(' '); origem[n++] = i
            }
            ultimoFoiEspaco = true
            continue
        }
        ultimoFoiEspaco = false
        for (ch in normalizarCaractere(c)) {
            if (n == origem.size) origem = origem.copyOf(n * 2)
            normal.append(ch); origem[n++] = i
        }
    }
    return TextoBuscavel(normal.toString(), origem.copyOf(n))
}

/** A consulta digitada no mesmo formato de [TextoBuscavel.normal] (sem espaços nas pontas). */
fun normalizarConsulta(consulta: String): String = prepararParaBusca(consulta).normal.trim()

/**
 * Trechos do texto ORIGINAL (posições de caractere) onde [consultaNormal] aparece, ignorando
 * maiúsculas, acentos, tipo de aspas e espaços repetidos. Vazio se não aparece.
 */
fun ocorrenciasBusca(texto: TextoBuscavel, consultaNormal: String): List<IntRange> {
    if (consultaNormal.isEmpty()) return emptyList()
    val achados = mutableListOf<IntRange>()
    var de = 0
    while (true) {
        val i = texto.normal.indexOf(consultaNormal, de)
        if (i < 0) break
        val fim = i + consultaNormal.length - 1
        achados += texto.origem[i]..texto.origem[fim]
        de = i + consultaNormal.length
    }
    return achados
}

/**
 * Índice (dentro de [resultados]) do primeiro resultado a partir de [paragrafoNaTela] — a busca
 * começa onde o leitor está, não no começo do livro. Se não há nenhum depois, volta ao primeiro.
 */
fun indiceInicialBusca(resultados: List<Int>, paragrafoNaTela: Int): Int {
    if (resultados.isEmpty()) return -1
    val i = resultados.indexOfFirst { it >= paragrafoNaTela }
    return if (i >= 0) i else 0
}
