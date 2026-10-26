package com.jonjonesbr.audiobookgen.tts

/**
 * Ajustes no texto antes do Pocket TTS. O modelo não sabe o que fazer com reticências ("...", "…", ". . .") e
 * pontuação repetida ("?!", "!!!"): tenta pronunciá-las e sai um som sem sentido. Aqui elas viram a pontuação
 * que ele lê bem — vírgula no meio da frase (uma pausa curta), ponto no fim ou antes de nova frase — e o resto
 * do texto fica igual.
 */
object TextoPocket {

    /** Duas ou mais reticências/pontos seguidos, com ou sem espaço entre eles, ou o caractere "…". */
    private val RETICENCIAS = Regex("""(?:…|\.(?:[ \t]*\.)+)+""")
    private val PONTUACAO_REPETIDA = Regex("""([!?])[!?]+""")
    /** Vírgula colada a outra pontuação (ex.: "Não,?" depois de trocar as reticências). */
    private val VIRGULA_SOBRANDO = Regex(""",\s*([,.;:!?])""")
    private val ESPACOS = Regex("""[ \t]{2,}""")
    private val ESPACO_ANTES_DE_PONTUACAO = Regex("""[ \t]+([,.;:!?])""")
    private const val FECHAMENTOS = "\"'”’»)]"

    fun normalizar(texto: String): String {
        if ('.' !in texto && '…' !in texto && '!' !in texto && '?' !in texto) return texto
        val sb = StringBuilder()
        var ultimo = 0
        for (m in RETICENCIAS.findAll(texto)) {
            sb.append(texto, ultimo, m.range.first)
            sb.append(substituto(texto, m.range.first, m.range.last + 1))
            ultimo = m.range.last + 1
        }
        sb.append(texto, ultimo, texto.length)
        return sb.toString()
            .replace(PONTUACAO_REPETIDA, "$1")
            .replace(VIRGULA_SOBRANDO, "$1")
            .replace(ESPACO_ANTES_DE_PONTUACAO, "$1")
            .replace(ESPACOS, " ")
            .trim()
    }

    /** Começo do trecho: some; fim: ponto; antes de "?"/"!": nada; antes de maiúscula: ponto; no meio: vírgula. */
    private fun substituto(texto: String, inicio: Int, fim: Int): String {
        val antes = texto.substring(0, inicio).trimEnd()
        val depois = texto.substring(fim).trimStart()
        val proximo = depois.firstOrNull { it !in FECHAMENTOS }
        return when {
            antes.none { it.isLetterOrDigit() } -> ""
            proximo == null -> "."
            proximo in "!?,;:" -> ""
            proximo.isUpperCase() -> ". "
            else -> ", "
        }
    }
}
