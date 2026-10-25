package com.jonjonesbr.audiobookgen.domain

import java.text.Normalizer

/**
 * Regras puras da barra de capítulo do leitor e da lista de capítulos: qual é o capítulo da posição de
 * leitura, títulos legíveis (os do arquivo vêm em CAIXA ALTA, com espaços sobrando) e quais entradas são
 * partes técnicas do livro (capa, direitos autorais, sumário), mostradas discretas na lista.
 */
object NavegacaoCapitulos {

    private val ESPACOS = Regex("\\s+")
    private val MARCAS = Regex("\\p{M}+")
    private val ROMANO = Regex("(?=[IVXLCDM])M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")
    private const val FIM_DE_FRASE = ".:!?—–-"
    private val PARTES_TECNICAS = listOf(
        "capa", "cover", "folha de rosto", "pagina de rosto", "title page", "frontispicio", "direitos",
        "copyright", "creditos", "credits", "ficha catalografica", "isbn", "colofao", "colophon", "sumario",
        "indice", "contents", "table of contents", "sobre a editora", "about the publisher"
    ).map { Regex("\\b${Regex.escape(it)}\\b") }

    /** Capítulo que contém o parágrafo [indiceParagrafo] (o último que começa até ele); -1 se nenhum. */
    fun indiceDoCapitulo(capitulos: List<Capitulo>, indiceParagrafo: Int): Int =
        capitulos.indexOfLast { it.indiceParagrafoInicio <= indiceParagrafo }

    /** "CAPA PÁGINA" → "Capa página"; "CAPÍTULO IV" → "Capítulo IV". Títulos com minúsculas ficam como estão. */
    fun tituloLimpo(titulo: String): String {
        val limpo = titulo.trim().replace(ESPACOS, " ")
        val letras = limpo.filter { it.isLetter() }
        if (letras.length < 2 || letras.any { it.isLowerCase() }) return limpo
        val palavras = limpo.split(' ')
        return palavras.mapIndexed { i, palavra ->
            val anterior = palavras.getOrNull(i - 1)
            when {
                ROMANO.matches(palavra.trimEnd('.', ',', ':', ';')) -> palavra
                anterior == null || anterior.last() in FIM_DE_FRASE ->
                    palavra.lowercase().replaceFirstChar { it.titlecase() }
                else -> palavra.lowercase()
            }
        }.joinToString(" ")
    }

    /** Capa, folha de rosto, direitos autorais, sumário etc.: partes do livro que não são capítulos. */
    fun ehParteTecnica(titulo: String): Boolean {
        val chave = chave(titulo)
        return PARTES_TECNICAS.any { it.containsMatchIn(chave) }
    }

    /** Posições de [capitulos] cujo título contém [busca] (sem diferenciar maiúsculas nem acentos). */
    fun filtrar(capitulos: List<Capitulo>, busca: String): List<Int> {
        val alvo = chave(busca.trim())
        return capitulos.indices.filter { alvo.isEmpty() || chave(capitulos[it].titulo).contains(alvo) }
    }

    private fun chave(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD).replace(MARCAS, "").lowercase()
}
