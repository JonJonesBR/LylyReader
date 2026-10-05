package com.jonjonesbr.audiobookgen.util

/**
 * Detecta o idioma provável de um texto por análise de palavras comuns.
 * Suporta: português, inglês, espanhol.
 */
object LanguageDetector {

    private val PT_WORDS = setOf(
        "o", "a", "de", "que", "é", "em", "para", "um", "com", "não",
        "uma", "por", "do", "da", "e", "à", "se", "na", "este", "aquele",
        "ou", "seja", "como", "mas", "foi", "ao", "ele", "os", "das"
    )

    private val EN_WORDS = setOf(
        "the", "a", "is", "and", "in", "to", "of", "for", "be", "have",
        "that", "it", "you", "this", "but", "his", "by", "from", "they", "are"
    )

    private val ES_WORDS = setOf(
        "el", "la", "de", "que", "y", "a", "en", "un", "ser", "se",
        "no", "haber", "por", "con", "su", "para", "es", "una", "o", "este"
    )

    // \p{L}/\p{N} (property escapes Unicode) em vez de \w/\W: \w é ASCII-only por padrão em
    // Java/Kotlin ([a-zA-Z0-9_]) — palavras acentuadas (ex.: "não", "é") ficavam fragmentadas
    // nas letras acentuadas ("não" virava "n"+"o"), perdendo o sinal mais forte de pt-BR/es-ES
    // na pontuação. A flag inline (?U) resolvia isso na JVM de desktop, mas quebrava a
    // inicialização deste objeto em runtime real no Android (ART) — \p{L}/\p{N} são
    // Unicode-aware por definição, sem depender de nenhuma flag, e funcionam nos dois.
    private val REGEX_QUEBRA_PALAVRAS = Regex("[^\\p{L}\\p{N}]+")

    fun detectLanguage(text: String, sampleSize: Int = 500): String {
        val sample = text.take(sampleSize).lowercase()
        val words = sample.split(REGEX_QUEBRA_PALAVRAS)
            .filter { it.isNotBlank() }

        var ptScore = 0
        var enScore = 0
        var esScore = 0

        for (word in words) {
            if (word in PT_WORDS) ptScore++
            if (word in EN_WORDS) enScore++
            if (word in ES_WORDS) esScore++
        }

        return when (maxOf(ptScore, enScore, esScore)) {
            ptScore -> "pt-BR"
            enScore -> "en-US"
            esScore -> "es-ES"
            else -> "pt-BR" // default
        }
    }
}
