package com.jonjonesbr.audiobookgen.domain

/**
 * Nome e autor que a aba Audiobooks mostra, a partir das etiquetas do MP3 e do nome do arquivo.
 * Só para exibir: o arquivo nunca é renomeado.
 */
object NomeDoAudiobook {

    /** Artista que o app gravava em todo MP3 gerado (TPE1) antes do autor real. Não é o autor do livro. */
    private const val ARTISTA_PADRAO_DO_APP = "LylyReader"

    /**
     * Compositor (TCOM) que o app grava nos MP3 gerados a partir da versão com autor real. O MediaMetadataRetriever
     * não lê "encoded by", então este campo é o marcador. Com ele, o TIT2 é o título do livro e o TPE1 o autor.
     * Igual a `MARCADOR_DO_APP` em core_processor_android.py.
     */
    const val MARCADOR_DO_APP = "LylyReader"

    private val ESPACOS = Regex("\\s+")

    /**
     * Nome que aparece no cartão. Usa o título da etiqueta, se houver (ver [tituloDaEtiqueta]).
     * Senão o nome do arquivo sem a extensão de áudio, com "_" trocado por espaço e espaços repetidos juntos.
     */
    fun paraMostrar(
        tituloTag: String?,
        artistaTag: String?,
        nomeArquivo: String,
        compositorTag: String? = null
    ): String {
        tituloDaEtiqueta(tituloTag, artistaTag, compositorTag)?.let { return it }
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
     * Título da etiqueta, sem vazio. Num MP3 gerado pelo app com o marcador ([MARCADOR_DO_APP] no compositor), o TIT2
     * é o título do livro e vale. Sem o marcador, o TIT2 de MP3 do app antigo é o nome do arquivo de entrada da
     * conversão (ex.: "reader_selection_123"), e a regra é ignorá-lo quando o artista é o do app.
     */
    fun tituloDaEtiqueta(tituloTag: String?, artistaTag: String?, compositorTag: String? = null): String? {
        val confiavel = ehMarcadorDoApp(compositorTag) || !ehArtistaDoApp(artistaTag)
        return if (confiavel) tituloTag?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    /**
     * Autor da etiqueta, ou null se estiver vazia ou for o artista padrão do app (MP3 antigo, sem autor real).
     * Nos MP3 com o marcador, o TPE1 é o autor real; se o autor for desconhecido, o TPE1 fica com o artista padrão.
     */
    fun autorDaEtiqueta(artistaTag: String?): String? {
        if (ehArtistaDoApp(artistaTag)) return null
        return artistaTag?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Artista igual ao do app, sem diferença de maiúsculas e ignorando espaços (ex.: " lylyreader "). */
    private fun ehArtistaDoApp(artistaTag: String?): Boolean = mesmoTextoDoApp(artistaTag, ARTISTA_PADRAO_DO_APP)

    /** Compositor igual ao marcador do app, com a mesma normalização do artista. */
    private fun ehMarcadorDoApp(compositorTag: String?): Boolean = mesmoTextoDoApp(compositorTag, MARCADOR_DO_APP)

    private fun mesmoTextoDoApp(valor: String?, esperado: String): Boolean {
        val semEspacos = valor?.filterNot { it.isWhitespace() } ?: return false
        return semEspacos.equals(esperado, ignoreCase = true)
    }
}
