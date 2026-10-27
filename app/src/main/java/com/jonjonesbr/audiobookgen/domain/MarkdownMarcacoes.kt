package com.jonjonesbr.audiobookgen.domain

/** Uma marcação pronta para exportar: capítulo, trecho, nota e data já em forma de texto. */
data class MarcacaoExportavel(
    val capitulo: String,
    val trecho: String,
    val nota: String,
    val data: String,
)

/**
 * Monta o Markdown da exportação de marcadores e destaques (item 12.1). Função pura, sem Android,
 * para ser testada na JVM. Os rótulos já chegam traduzidos de fora ([rotuloNota]).
 */
object MarkdownMarcacoes {

    fun montar(titulo: String, marcacoes: List<MarcacaoExportavel>, rotuloNota: String): String {
        val blocos = mutableListOf<String>()
        if (titulo.isNotBlank()) blocos += "# ${titulo.trim()}"
        marcacoes.forEach { m ->
            val partes = mutableListOf<String>()
            // Capítulo vira cabeçalho; sem capítulo conhecido, a marcação fica só com o trecho.
            if (m.capitulo.isNotBlank()) partes += "## ${m.capitulo.trim()}"
            // Trecho em citação, linha por linha, para não quebrar o bloco do Markdown.
            partes += m.trecho.trim().lines().joinToString("\n") { "> $it" }
            if (m.nota.isNotBlank()) partes += "**$rotuloNota:** ${m.nota.trim()}"
            if (m.data.isNotBlank()) partes += "*${m.data}*"
            blocos += partes.joinToString("\n\n")
        }
        return blocos.joinToString("\n\n") + "\n"
    }
}
