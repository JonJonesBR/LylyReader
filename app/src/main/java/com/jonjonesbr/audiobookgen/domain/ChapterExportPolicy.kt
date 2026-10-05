package com.jonjonesbr.audiobookgen.domain

/** Monta os arquivos .txt (um por capítulo) pra exportação — extraído de [com.jonjonesbr.audiobookgen.ui.ReaderActivity]
 * pra ser testável isoladamente. */
object ChapterExportPolicy {
    private const val NOME_ARQUIVO_CAPITULO_LIMITE = 70

    fun montarArquivosCapitulos(
        capitulos: List<Capitulo>,
        paragrafos: List<Paragrafo>
    ): List<Pair<String, String>> =
        capitulos.mapIndexed { index, cap ->
            // coerceAtLeast(inicio) na ponta final garante fromIndex <= toIndex mesmo se os
            // indices do capitulo vierem inconsistentes (ex: inicio > fim), evitando
            // IllegalArgumentException do subList().
            val inicio = cap.indiceParagrafoInicio.coerceIn(0, paragrafos.size)
            val fim = (cap.indiceParagrafoFim + 1).coerceIn(inicio, paragrafos.size)
            val conteudo = paragrafos.subList(inicio, fim).joinToString("\n\n") { it.texto }
            "%02d - %s.txt".format(index + 1, sanitizarNomeArquivoCapitulo(cap.titulo)) to conteudo
        }

    fun sanitizarNomeArquivoCapitulo(titulo: String): String {
        val limpo = titulo.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(NOME_ARQUIVO_CAPITULO_LIMITE)
        return limpo.ifBlank { "sem_titulo" }
    }
}
