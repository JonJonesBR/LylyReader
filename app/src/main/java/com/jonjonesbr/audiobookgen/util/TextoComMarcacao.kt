package com.jonjonesbr.audiobookgen.util

/** Tipo de um trecho do texto-guia: normal, negrito (`**palavra**`) ou dica de entonação (`_(dica)_`). */
enum class TipoTrecho { NORMAL, NEGRITO, DICA }

data class Trecho(val tipo: TipoTrecho, val texto: String)

/**
 * Lê a marcação simples dos textos de leitura da clonagem. Marcadores sem par ficam como texto comum.
 * O texto sem marcadores (o que o TalkBack lê) sai de [semMarcacao].
 */
object TextoComMarcacao {
    private val marcacao = Regex("""\*\*(.+?)\*\*|_\((.+?)\)_""")

    fun analisar(texto: String): List<Trecho> {
        val trechos = mutableListOf<Trecho>()
        var fim = 0
        for (achado in marcacao.findAll(texto)) {
            if (achado.range.first > fim) {
                trechos += Trecho(TipoTrecho.NORMAL, texto.substring(fim, achado.range.first))
            }
            val negrito = achado.groups[1]
            trechos += if (negrito != null) {
                Trecho(TipoTrecho.NEGRITO, negrito.value)
            } else {
                Trecho(TipoTrecho.DICA, achado.groups[2]!!.value)
            }
            fim = achado.range.last + 1
        }
        if (fim < texto.length) trechos += Trecho(TipoTrecho.NORMAL, texto.substring(fim))
        return trechos
    }

    fun semMarcacao(texto: String): String = analisar(texto).joinToString("") { it.texto }
}
