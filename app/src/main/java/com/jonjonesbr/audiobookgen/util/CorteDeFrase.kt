package com.jonjonesbr.audiobookgen.util

import kotlin.math.abs

/**
 * Corte de uma frase longa da leitura guiada em partes de tamanho parecido, sem deixar palavra de ligação pendurada
 * no fim de uma parte. Puro, sem Android. As partes são grupos consecutivos de palavras do original, unidos por
 * espaço simples ([DivisorDeOracoes.partirLongas] localiza cada uma no texto real).
 */
object CorteDeFrase {

    /**
     * Frase maior que [maxChars] vira partes de tamanho parecido (corte balanceado, não guloso), para o trecho
     * final não sair como uma sobra de poucas palavras. Cada corte escolhe, entre as posições permitidas, a de
     * melhor categoria e depois a mais perto do tamanho-alvo: pontuação de pausa ([CATEGORIA_PONTUACAO]), antes de
     * conjunção ou relativo ([CATEGORIA_CONJUNCAO]), fronteira neutra ([CATEGORIA_NEUTRA]). Nunca se corta depois
     * de palavra que não termina parte ([NAO_TERMINA_PARTE]). Se nenhum corte serve, relaxa o tamanho mínimo e,
     * por fim, corta guloso por palavra. Não corta palavra: uma palavra maior que o limite fica inteira.
     */
    fun partir(frase: String, maxChars: Int): List<String> {
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
