package com.jonjonesbr.audiobookgen.util

import kotlin.math.abs

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
     * Frase maior que [maxChars] vira partes de tamanho parecido (corte balanceado, não guloso), para o trecho
     * final não sair como uma sobra de poucas palavras. Cada corte escolhe, entre as posições permitidas, a de
     * melhor categoria e depois a mais perto do tamanho-alvo: pontuação de pausa ([CATEGORIA_PONTUACAO]), antes de
     * conjunção ou relativo ([CATEGORIA_CONJUNCAO]), fronteira neutra ([CATEGORIA_NEUTRA]). Nunca se corta depois
     * de palavra que não termina parte ([NAO_TERMINA_PARTE]). Se nenhum corte serve, relaxa o tamanho mínimo e,
     * por fim, corta guloso por palavra. Não corta palavra: uma palavra maior que o limite fica inteira.
     */
    fun partirFrase(frase: String, maxChars: Int): List<String> {
        if (frase.length <= maxChars) return listOf(frase)
        val palavras = frase.split(ESPACOS).filter { it.isNotEmpty() }
        val partes = mutableListOf<List<String>>()
        var inicio = 0
        while (inicio < palavras.size) {
            val restante = palavras.subList(inicio, palavras.size)
            if (comprimento(restante) <= maxChars) {
                partes.add(restante)
                break
            }
            val fim = escolherCorte(palavras, inicio, maxChars)
            partes.add(palavras.subList(inicio, fim))
            inicio = fim
        }
        juntarSobraPequena(partes)
        return partes.map { it.joinToString(" ") }
    }

    /**
     * Fim (exclusivo, em índice de palavra) da parte que começa em [inicio]: balanceado, depois relaxado,
     * depois guloso.
     */
    private fun escolherCorte(palavras: List<String>, inicio: Int, maxChars: Int): Int =
        melhorCorte(palavras, inicio, maxChars, PARTE_MIN, soNeutro = false)
            ?: melhorCorte(palavras, inicio, maxChars, PARTE_MINIMA_RELAXADA, soNeutro = true)
            ?: fimGuloso(palavras, inicio, maxChars)

    /**
     * Melhor fim de parte entre as posições em que a parte fica entre [minimo] e [maxChars] e a sobra não é
     * pequena demais. O alvo é o restante dividido pelo número de partes que faltam. Null quando nenhuma posição
     * serve. Com [soNeutro], só a fronteira neutra vale.
     */
    private fun melhorCorte(
        palavras: List<String>,
        inicio: Int,
        maxChars: Int,
        minimo: Int,
        soNeutro: Boolean
    ): Int? {
        val restante = comprimento(palavras.subList(inicio, palavras.size))
        val alvo = restante / ((restante + maxChars - 1) / maxChars)
        var melhor: Int? = null
        var melhorPeso = Int.MAX_VALUE
        var fim = inicio + 1
        // Tamanho da parte que termina antes de [fim] (palavras inicio até fim - 1).
        var tamanho = palavras[inicio].length
        while (fim < palavras.size && tamanho <= maxChars) {
            val sobra = restante - tamanho - 1
            if (tamanho >= minimo && sobra >= SOBRA_MIN) {
                val peso = pesoDoCorte(palavras, fim, tamanho, alvo, soNeutro)
                if (peso < melhorPeso) {
                    melhorPeso = peso
                    melhor = fim
                }
            }
            tamanho += 1 + palavras[fim].length
            fim++
        }
        return melhor
    }

    /**
     * Peso do corte antes da palavra [fim]: categoria primeiro, depois a distância ao [alvo].
     * Sem permissão, [Int.MAX_VALUE].
     */
    private fun pesoDoCorte(palavras: List<String>, fim: Int, tamanho: Int, alvo: Int, soNeutro: Boolean): Int {
        val categoria = categoriaDoCorte(palavras, fim)
        val permitido = categoria != PROIBIDO && (!soNeutro || categoria == CATEGORIA_NEUTRA)
        return if (permitido) categoria * PESO_DA_CATEGORIA + abs(tamanho - alvo) else Int.MAX_VALUE
    }

    /** Categoria do corte antes da palavra [fim] ([PROIBIDO] quando a parte anterior não pode terminar nela). */
    private fun categoriaDoCorte(palavras: List<String>, fim: Int): Int {
        val anterior = palavras[fim - 1]
        return when {
            semPontuacaoFinal(anterior) in NAO_TERMINA_PARTE -> PROIBIDO
            anterior.last() in PONTUACAO_DE_CORTE -> CATEGORIA_PONTUACAO
            semPontuacaoFinal(palavras[fim]) in ABRE_ORACAO -> CATEGORIA_CONJUNCAO
            else -> CATEGORIA_NEUTRA
        }
    }

    /** Corte guloso: a parte que começa em [inicio] vai até a última palavra que ainda cabe em [maxChars]. */
    private fun fimGuloso(palavras: List<String>, inicio: Int, maxChars: Int): Int {
        var fim = inicio + 1
        while (fim < palavras.size && comprimento(palavras.subList(inicio, fim + 1)) <= maxChars) fim++
        return fim
    }

    /** Sobra final menor que [SOBRA_MIN] (só sobra do corte guloso) é juntada à parte anterior e refeita ao meio. */
    private fun juntarSobraPequena(partes: MutableList<List<String>>) {
        if (partes.size < 2 || comprimento(partes.last()) >= SOBRA_MIN) return
        val sobra = partes.removeAt(partes.lastIndex)
        val anterior = partes.removeAt(partes.lastIndex)
        val junto = anterior + sobra
        val meio = dividirAoMeio(junto)
        partes.add(junto.subList(0, meio))
        partes.add(junto.subList(meio, junto.size))
    }

    /** Índice (em palavras) do corte que deixa a 1ª metade de [palavras] mais perto da metade do texto. */
    private fun dividirAoMeio(palavras: List<String>): Int {
        val total = comprimento(palavras)
        return (1 until palavras.size).minByOrNull {
            abs(comprimento(palavras.subList(0, it)) * 2 - total)
        } ?: 1
    }

    /** Tamanho em chars de [palavras] unidas por espaço simples. */
    private fun comprimento(palavras: List<String>): Int =
        palavras.sumOf { it.length } + (palavras.size - 1).coerceAtLeast(0)

    /** A palavra sem pontuação final, em minúsculas: base da comparação com as listas. */
    private fun semPontuacaoFinal(palavra: String): String =
        palavra.trimEnd { !it.isLetterOrDigit() }.lowercase()

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

    /** Parte cortada no meio da frase tem pelo menos isso (abaixo, vira fala solta com entonação de fim). */
    private const val PARTE_MIN = 35
    /** Relaxamento da busca de corte: aceita partes menores que [PARTE_MIN], só na fronteira neutra. */
    private const val PARTE_MINIMA_RELAXADA = 1
    /** Sobra que sobraria depois do corte com menos que isso não é aceita (e a sobra final é juntada). */
    private const val SOBRA_MIN = 15
    /** Peso da categoria no corte: deve passar de qualquer distância ao alvo possível. */
    private const val PESO_DA_CATEGORIA = 10_000

    private const val PROIBIDO = -1
    private const val CATEGORIA_PONTUACAO = 0
    private const val CATEGORIA_CONJUNCAO = 1
    private const val CATEGORIA_NEUTRA = 2

    /** Pontuação de pausa depois da qual o corte é preferido (travessões incluídos). */
    private const val PONTUACAO_DE_CORTE = ",;:—–"

    /** Palavras que abrem oração ou relativo: o corte cai logo antes delas. */
    private val ABRE_ORACAO = setOf(
        "e", "mas", "ou", "que", "quando", "porque", "pois", "onde", "como", "enquanto", "embora", "se"
    )

    /**
     * Palavras que não podem terminar uma parte (comparadas sem pontuação final, em minúsculas): artigos,
     * preposições e contrações, conjunções e relativos, possessivos, advérbios curtos de ligação
     * e pronomes átonos.
     */
    private val NAO_TERMINA_PARTE = setOf(
        // artigos e determinantes
        "o", "a", "os", "as", "um", "uma", "uns", "umas",
        // preposições e contrações
        "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas", "ao", "aos", "à", "às",
        "por", "pelo", "pela", "pelos", "pelas", "para", "pra", "com", "sem", "sob", "sobre", "até",
        "entre", "contra", "desde", "num", "numa", "dum", "duma",
        // conjunções e relativos
        "e", "ou", "mas", "que", "se", "como", "quando", "porque", "pois", "onde", "enquanto", "embora",
        // possessivos
        "meu", "minha", "meus", "minhas", "teu", "tua", "teus", "tuas", "seu", "sua", "seus", "suas",
        "nosso", "nossa", "nossos", "nossas",
        // advérbios curtos de ligação
        "não", "já", "mais", "muito", "tão",
        // pronomes átonos
        "me", "te", "lhe", "lhes", "nos", "vos"
    )
}
