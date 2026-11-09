package com.jonjonesbr.audiobookgen.util

/**
 * Unidade de fala da leitura guiada: um trecho do parágrafo que termina em pontuação
 * de frase (`.`, `!`, `?`, `…`), ou uma parte de frase longa (ver [partirLongas]).
 * Sintetizar por oração em vez do parágrafo inteiro reduz a latência até a primeira voz e
 * encurta os silêncios entre blocos quando o motor local tem RTF > 1 (a próxima oração é
 * sintetizada enquanto a atual toca).
 */
data class OracaoGuia(
    /** Texto da oração (sem espaços das bordas) — o que vai para o sintetizador. */
    val texto: String,
    /** Posição (em chars) onde a oração começa no parágrafo, com os espaços entre as frases — base do progresso. */
    val charsAcumulados: Int,
    /**
     * Verdadeiro quando este trecho é a continuação de uma frase longa dividida por [DivisorDeOracoes.partirLongas]
     * (a anterior termina no meio da frase). Entre elas a pausa é curta, não a de fim de frase.
     */
    val continuacao: Boolean = false
)

/**
 * Divide um parágrafo em orações para síntese incremental da leitura guiada.
 *
 * Regra: uma oração termina após uma sequência de pontuação de frase
 * ([.!?…]) seguida opcionalmente por aspas/parênteses de fechamento, quando o
 * próximo caractere é espaço/fim. Trechos sem pontuação viram uma oração única
 * (fallback). O texto devolvido em [OracaoGuia.texto] é limpo nas bordas (sem
 * espaços), e [OracaoGuia.charsAcumulados] é a posição real do início da
 * oração no parágrafo (base para mapear o progresso de áudio em progresso de
 * texto; somar só os tamanhos deixava o destaque um char atrás por frase).
 * Função pura — sem estado, sem dependência de Android.
 */
object DivisorDeOracoes {

    /** Caracteres que ficam colados à pontuação final (reticências, aspas, parênteses) e entram no mesmo trecho. */
    private const val FECHAMENTOS_DEPOIS_DA_PONTUACAO = "\u2026.!?\"\u201D'\u2019)\u00BB\u203A"

    fun dividirEmOracoes(texto: String): List<OracaoGuia> {
        if (texto.isBlank()) return emptyList()

        val oracoes = mutableListOf<OracaoGuia>()
        var inicio = 0
        var i = 0
        val n = texto.length

        while (i < n) {
            val c = texto[i]
            if (c == '.' || c == '!' || c == '?' || c == '\u2026') {
                var fim = i + 1
                // Engloba reticências/combinações e fechamentos comuns depois da pontuação.
                while (fim < n && texto[fim] in FECHAMENTOS_DEPOIS_DA_PONTUACAO) {
                    fim++
                }
                val proximo = texto.getOrNull(fim)
                if ((proximo == null || proximo.isWhitespace()) && !terminaEmAbreviacao(texto, i, fim)) {
                    val trecho = texto.substring(inicio, fim).trim()
                    if (trecho.isNotEmpty()) oracoes.add(OracaoGuia(trecho, inicioReal(texto, inicio)))
                    inicio = fim
                    while (inicio < n && texto[inicio].isWhitespace()) inicio++
                    i = inicio
                    continue
                }
            }
            i++
        }

        if (inicio < n) {
            val trecho = texto.substring(inicio).trim()
            if (trecho.isNotEmpty()) oracoes.add(OracaoGuia(trecho, inicioReal(texto, inicio)))
        }
        return oracoes
    }

    /** Primeira posição a partir de [desde] que não é espaço (onde o trecho aparado começa). */
    private fun inicioReal(texto: String, desde: Int): Int {
        var p = desde
        while (p < texto.length && texto[p].isWhitespace()) p++
        return p
    }

    // Abreviações de tratamento/título que antecedem um nome: "Dr. Silva" não é fim de frase.
    // Cortar ali deixava "Dr." sozinho para o motor, que lia um ruído ou pulava a palavra.
    private val ABREVIACOES = setOf(
        "dr", "dra", "sr", "sra", "srta", "prof", "profa", "cel", "cap", "maj", "ten", "eng",
        "av", "sta", "sto", "pç", "pça", "lgo", "pág", "art", "vol", "nº", "exmo", "exma",
        "mr", "mrs", "ms", "st", "sgt", "cmte"
    )

    /** Pontuação única em [pos] fechando uma abreviação ("Dr.") ou inicial maiúscula ("J. K."). */
    private fun terminaEmAbreviacao(texto: String, pos: Int, fim: Int): Boolean {
        if (texto[pos] != '.' || fim != pos + 1) return false
        var ini = pos
        while (ini > 0 && texto[ini - 1].isLetter()) ini--
        val palavra = texto.substring(ini, pos)
        if (palavra.isEmpty()) return false
        // Só continua a frase se houver algo depois, começando por letra/dígito/aspas de abertura.
        var k = fim
        while (k < texto.length && texto[k].isWhitespace()) k++
        if (k >= texto.length) return false
        val seguinte = texto[k]
        if (!(seguinte.isLetterOrDigit() || seguinte == '"' || seguinte == '“' || seguinte == '\'')) return false
        if (palavra.lowercase() in ABREVIACOES) return seguinte.isUpperCase() || seguinte.isDigit()
        // Inicial solta ("J. K. Rowling", "Maria S. Costa"): uma maiúscula e seguinte maiúscula.
        return palavra.length == 1 && palavra[0].isUpperCase() && seguinte.isUpperCase()
    }

    /**
     * Agrupa orações curtas na oração seguinte (até [tetoChars]), para a leitura guiada
     * não gerar uma rajada de unidades minúsculas — cada unidade pequena toca rápido e,
     * com RTF > 1 no motor local, deixa o pipeline sem áudio pronto (silêncio na borda).
     * Unidades ficam entre [minimoChars] e [tetoChars] (~3-7s de áudio): curtas o bastante
     * para o déficit por unidade ((RTF-1) × duração) virar uma pausa curta e natural.
     */
    fun agruparOracoesCurta(oracoes: List<OracaoGuia>, minimoChars: Int = 45, tetoChars: Int = 110): List<OracaoGuia> {
        if (oracoes.size <= 1) return oracoes
        val agrupadas = mutableListOf<OracaoGuia>()
        var i = 0
        while (i < oracoes.size) {
            var texto = oracoes[i].texto
            var j = i + 1
            while (j < oracoes.size && texto.length < minimoChars &&
                (texto.length + oracoes[j].texto.length + 1) <= tetoChars
            ) {
                texto += " " + oracoes[j].texto
                j++
            }
            // O grupo começa onde começa a sua primeira oração.
            agrupadas.add(OracaoGuia(texto, oracoes[i].charsAcumulados))
            i = j
        }
        return agrupadas
    }

    /**
     * Frase maior que [maxChars] vira várias partes, para a síntese à frente não ficar atrás da voz: o motor
     * sintetiza cada parte numa unidade própria, e a 2ª só começava depois que a 1ª tocava. Orações até
     * [maxChars] ficam como estão. As partes depois da 1ª saem com `continuacao = true`.
     */
    fun partirLongas(oracoes: List<OracaoGuia>, maxChars: Int): List<OracaoGuia> {
        val resultado = mutableListOf<OracaoGuia>()
        for (oracao in oracoes) {
            if (oracao.texto.length <= maxChars) {
                resultado += oracao
            } else {
                resultado += localizarPartes(oracao, partirFrase(oracao.texto, maxChars))
            }
        }
        return resultado
    }

    /**
     * Frase maior que [maxChars]: corta depois de vírgula/ponto e vírgula/dois-pontos (a partir da metade do
     * limite) e, se não houver, entre palavras. Não corta palavra: uma palavra maior que o limite fica inteira.
     */
    fun partirFrase(frase: String, maxChars: Int): List<String> {
        if (frase.length <= maxChars) return listOf(frase)
        val partes = mutableListOf<String>()
        val atual = StringBuilder()
        for (palavra in frase.split(ESPACOS).filter { it.isNotEmpty() }) {
            if (atual.isNotEmpty() && atual.length + 1 + palavra.length > maxChars) {
                partes += atual.toString()
                atual.clear()
            }
            if (atual.isNotEmpty()) atual.append(' ')
            atual.append(palavra)
            if (palavra.last() in ",;:" && atual.length >= maxChars / 2) {
                partes += atual.toString()
                atual.clear()
            }
        }
        if (atual.isNotEmpty()) partes += atual.toString()
        return partes
    }

    /**
     * Posição real de cada parte no texto original da oração: anda palavra a palavra pelo texto, pulando qualquer
     * quantidade de espaço (o original pode ter espaço duplo ou quebra de linha), em vez de confiar em indexOf.
     */
    private fun localizarPartes(oracao: OracaoGuia, partes: List<String>): List<OracaoGuia> {
        val texto = oracao.texto
        var cursor = 0
        val resultado = mutableListOf<OracaoGuia>()
        for ((indice, parte) in partes.withIndex()) {
            cursor = pularEspacos(texto, cursor)
            val inicio = cursor
            for (palavra in parte.split(' ')) {
                cursor = pularEspacos(texto, cursor) + palavra.length
            }
            resultado += OracaoGuia(parte, oracao.charsAcumulados + inicio, continuacao = indice > 0)
        }
        return resultado
    }

    private fun pularEspacos(texto: String, de: Int): Int {
        var p = de
        while (p < texto.length && texto[p] in ESPACOS_DO_SPLIT) p++
        return p
    }

    /** Os mesmos espaços do `\s` do Java, que é o separador de [partirFrase]. */
    private const val ESPACOS_DO_SPLIT = " \t\n\u000B\u000C\r"
    private val ESPACOS = Regex("\\s+")
}
