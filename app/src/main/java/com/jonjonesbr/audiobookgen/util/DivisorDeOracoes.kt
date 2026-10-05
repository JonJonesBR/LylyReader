package com.jonjonesbr.audiobookgen.util

/**
 * Unidade de fala da leitura guiada: um trecho do parágrafo que termina em pontuação
 * de frase (`.`, `!`, `?`, `…`). Sintetizar por oração em vez do parágrafo inteiro
 * reduz a latência até a primeira voz e encurta os silêncios entre blocos quando o
 * motor local tem RTF > 1 (a próxima oração é sintetizada enquanto a atual toca).
 */
data class OracaoGuia(
    /** Texto da oração (sem espaços das bordas) — o que vai para o sintetizador. */
    val texto: String,
    /** Chars (das orações anteriores) acumulados no parágrafo — base do progresso. */
    val charsAcumulados: Int
)

/**
 * Divide um parágrafo em orações para síntese incremental da leitura guiada.
 *
 * Regra: uma oração termina após uma sequência de pontuação de frase
 * ([.!?…]) seguida opcionalmente por aspas/parênteses de fechamento, quando o
 * próximo caractere é espaço/fim. Trechos sem pontuação viram uma oração única
 * (fallback). O texto devolvido em [OracaoGuia.texto] é limpo nas bordas (sem
 * espaços), e [OracaoGuia.charsAcumulados] soma os tamanhos das orações
 * anteriores (base para mapear o progresso de áudio em progresso de texto no
 * parágrafo). Função pura — sem estado, sem dependência de Android.
 */
object DivisorDeOracoes {

    fun dividirEmOracoes(texto: String): List<OracaoGuia> {
        if (texto.isBlank()) return emptyList()

        val oracoes = mutableListOf<OracaoGuia>()
        var inicio = 0
        var i = 0
        var acumulado = 0
        val n = texto.length

        while (i < n) {
            val c = texto[i]
            if (c == '.' || c == '!' || c == '?' || c == '\u2026') {
                var fim = i + 1
                // Engloba reticências/combinações e fechamentos comuns depois da pontuação.
                while (fim < n && (
                        texto[fim] == '\u2026' || texto[fim] == '.' || texto[fim] == '!' ||
                            texto[fim] == '?' || texto[fim] == '"' || texto[fim] == '\u201D' ||
                            texto[fim] == '\'' || texto[fim] == '\u2019' || texto[fim] == ')'
                        )
                ) {
                    fim++
                }
                val proximo = texto.getOrNull(fim)
                if ((proximo == null || proximo.isWhitespace()) && !terminaEmAbreviacao(texto, i, fim)) {
                    val trecho = texto.substring(inicio, fim).trim()
                    if (trecho.isNotEmpty()) {
                        oracoes.add(OracaoGuia(trecho, acumulado))
                        acumulado += trecho.length
                    }
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
            if (trecho.isNotEmpty()) {
                oracoes.add(OracaoGuia(trecho, acumulado))
            }
        }
        return oracoes
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
        var acumulado = 0
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
            agrupadas.add(OracaoGuia(texto, acumulado))
            acumulado += texto.length
            i = j
        }
        return agrupadas
    }
}
