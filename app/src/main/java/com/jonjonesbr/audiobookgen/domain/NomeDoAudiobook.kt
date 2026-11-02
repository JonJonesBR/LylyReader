package com.jonjonesbr.audiobookgen.domain

/**
 * Nome e autor que a aba Audiobooks mostra, a partir das etiquetas do MP3 e do nome do arquivo.
 * Só para exibir: o arquivo nunca é renomeado.
 */
object NomeDoAudiobook {

    /** Artista que o próprio app grava em todo MP3 gerado (TPE1). Não é o autor do livro. */
    private const val ARTISTA_PADRAO_DO_APP = "LylyReader"

    private val ESPACOS = Regex("\\s+")

    /**
     * Nome que aparece no cartão. Usa o título da etiqueta, se houver (ver [tituloDaEtiqueta]).
     * Senão o nome do arquivo sem a extensão de áudio, com "_" trocado por espaço e espaços repetidos juntos.
     */
    fun paraMostrar(tituloTag: String?, artistaTag: String?, nomeArquivo: String): String {
        tituloDaEtiqueta(tituloTag, artistaTag)?.let { return it }
        val base = semExtensaoDeAudio(nomeArquivo)
        return base.replace('_', ' ').replace(ESPACOS, " ").trim().ifEmpty { base }
    }

    /**
     * Nome de um item da fila de conversão: o título do livro na Biblioteca, se ele tiver um; senão o nome do
     * arquivo sem a extensão, com "_" virando espaço. Não usa [semExtensaoDeAudio], porque o item da fila é um
     * .txt ou um .epub.
     */
    fun doItemDaFila(tituloDaBiblioteca: String?, nomeDoArquivo: String): String {
        tituloDaBiblioteca?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val base = nomeDoArquivo.substringBeforeLast('.', nomeDoArquivo)
        return base.replace('_', ' ').replace(ESPACOS, " ").trim().ifEmpty { nomeDoArquivo }
    }

    /**
     * Título da etiqueta, sem vazio. Não vale para MP3 gravado pelo app: nele o TIT2 é o nome do
     * arquivo de entrada da conversão (ex.: "reader_selection_123"), não o nome do audiobook.
     */
    fun tituloDaEtiqueta(tituloTag: String?, artistaTag: String?): String? {
        if (ehArtistaDoApp(artistaTag)) return null
        return tituloTag?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Autor da etiqueta, ou null se estiver vazia ou for o artista padrão do app. */
    fun autorDaEtiqueta(artistaTag: String?): String? {
        if (ehArtistaDoApp(artistaTag)) return null
        return artistaTag?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Artista igual ao do app, sem diferença de maiúsculas e ignorando espaços (ex.: " lylyreader "). */
    private fun ehArtistaDoApp(artistaTag: String?): Boolean {
        val semEspacos = artistaTag?.filterNot { it.isWhitespace() } ?: return false
        return semEspacos.equals(ARTISTA_PADRAO_DO_APP, ignoreCase = true)
    }
}
