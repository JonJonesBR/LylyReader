package com.jonjonesbr.audiobookgen.domain

/** Uma marcação pronta para exportar: capítulo, trecho e nota já em forma de texto. */
data class MarcacaoExportavel(
    val capitulo: String,
    val trecho: String,
    val nota: String,
)

/**
 * Monta o texto da exportação de marcadores e destaques (item M3). Função pura, sem Android,
 * para ser testada na JVM. Texto simples, sem Markdown: o resultado vai para o compartilhamento
 * do sistema, onde os símbolos apareceriam crus. Os rótulos já chegam traduzidos de fora ([rotuloNota]).
 */
object TextoExportacaoMarcacoes {

    fun montar(titulo: String, marcacoes: List<MarcacaoExportavel>, rotuloNota: String): String {
        val blocos = mutableListOf<String>()
        if (titulo.isNotBlank()) blocos += titulo.trim()
        marcacoes.forEach { m ->
            // Cada marcação é um bloco: trecho, depois nota e capítulo, uma linha por item.
            val linhas = listOfNotNull(
                m.trecho.trim().ifBlank { null },
                m.nota.trim().takeIf { it.isNotBlank() }?.let { "$rotuloNota: $it" },
                m.capitulo.trim().takeIf { it.isNotBlank() }?.let { "($it)" },
            )
            blocos += linhas.joinToString("\n")
        }
        return blocos.joinToString("\n\n") + "\n"
    }
}
