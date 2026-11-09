package com.jonjonesbr.audiobookgen.tts

/**
 * Ajustes do texto antes da síntese Supertonic. O motor nativo trocava o travessão de diálogo ('—') por vírgula,
 * e o trecho começava com ", ": o modelo pausava antes da 1ª palavra e a fala saía mais lenta. Aqui o travessão
 * sai (ou vira vírgula, quando está no meio da frase) antes de o texto ser dividido em trechos.
 *
 * Função pura: só muda o texto que vai ao sintetizador. O texto exibido e o destaque continuam os originais.
 */
object TextoSupertonic {

    /** Travessão (ou traço com espaço) no começo: "— Sim." vira "Sim.". */
    private val TRAVESSAO_DE_ABERTURA = Regex("^(?:\u2014\\s*|[\u2013-]\\s+)")

    /** Travessão no fim: "Ele disse —" vira "Ele disse". O en dash só conta com espaço antes. */
    private val TRAVESSAO_DE_FECHAMENTO = Regex("\\s*\u2014\\s*$|\\s+\u2013\\s*$")

    /**
     * Travessão no meio da frase, com os espaços em volta. O en dash só conta com espaço dos dois lados,
     * para intervalos como "10–20" não virarem "10, 20".
     */
    private val TRAVESSAO_DO_MEIO = Regex("\\s*\u2014\\s*|\\s+\u2013\\s+")

    private val ESPACOS = Regex("\\s+")

    /** Pontuação que já separa as falas: o travessão some e basta um espaço. */
    private const val PONTUACAO_QUE_JA_SEPARA = ".!?\u2026,;:"

    /**
     * Devolve [texto] pronto para o Supertonic. Sem letra nem dígito depois do ajuste (texto só de travessão),
     * devolve o original.
     */
    fun preparar(texto: String): String {
        val limpo = texto.trim()
            .replace(TRAVESSAO_DE_ABERTURA, "")
            .replace(TRAVESSAO_DE_FECHAMENTO, "")
            .let { trocarTravessoesDoMeio(it) }
            .replace(ESPACOS, " ")
            .trim()
        return if (limpo.any { it.isLetterOrDigit() }) limpo else texto
    }

    /**
     * Travessão depois de pontuação de fim de frase (ou vírgula) só some; depois de letra ou número vira ", ",
     * como em "sai — explicou" -> "sai, explicou".
     */
    private fun trocarTravessoesDoMeio(texto: String): String =
        TRAVESSAO_DO_MEIO.replace(texto) { m ->
            val anterior = texto.getOrNull(m.range.first - 1) ?: return@replace ""
            if (anterior in PONTUACAO_QUE_JA_SEPARA) " " else ", "
        }
}
