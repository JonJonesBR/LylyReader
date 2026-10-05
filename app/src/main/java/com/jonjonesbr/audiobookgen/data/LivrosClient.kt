package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.CatalogoArchive
import com.jonjonesbr.audiobookgen.domain.CatalogoGutenberg
import com.jonjonesbr.audiobookgen.domain.CatalogoWikisource
import com.jonjonesbr.audiobookgen.domain.FonteLivros
import com.jonjonesbr.audiobookgen.domain.IdiomaBusca
import com.jonjonesbr.audiobookgen.domain.LivroEncontrado
import com.jonjonesbr.audiobookgen.domain.ResultadoBusca
import com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Busca e download de livros em fontes legais (Projeto Gutenberg via OPDS; Wikisource via API do MediaWiki
 * e `ws-export`). Um pedido por busca, sem varredura; o download usa a primitiva retomável dos pacotes de voz.
 */
object LivrosClient {
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "LylyReader/1 (leitor de audiobooks; contato via GitHub JonJonesBR/LylyReader)"
    private const val MIN_EPUB_BYTES = 1024L
    private const val MAX_RESPOSTA_BYTES = 2L * 1024L * 1024L
    /** O `metadata` de itens com muitos arquivos é grande. */
    private const val MAX_METADATA_BYTES = 8L * 1024L * 1024L

    /** [inicio]: Gutenberg = `start_index` (1 = primeira); Wikisource = deslocamento (0 = primeira). */
    suspend fun buscar(fonte: FonteLivros, consulta: String, idioma: IdiomaBusca, inicio: Int?): ResultadoBusca =
        withContext(Dispatchers.IO) {
            when (fonte) {
                FonteLivros.GUTENBERG -> {
                    val texto = obter(CatalogoGutenberg.urlBusca(consulta, idioma, inicio ?: 1))
                    CatalogoGutenberg.interpretar(texto)
                }
                FonteLivros.WIKISOURCE -> {
                    // A API do Wikisource não aceita busca vazia.
                    if (consulta.isBlank()) return@withContext ResultadoBusca(emptyList(), null)
                    val texto = obter(CatalogoWikisource.urlBusca(consulta, idioma, inicio ?: 0))
                    CatalogoWikisource.interpretar(texto, idioma)
                }
                FonteLivros.ARCHIVE -> {
                    val pagina = inicio ?: 1
                    val texto = obter(CatalogoArchive.urlBusca(consulta, idioma, pagina))
                    CatalogoArchive.interpretarBusca(texto, pagina)
                }
            }
        }

    private fun obter(url: String, limite: Long = MAX_RESPOSTA_BYTES): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("A fonte respondeu ${conn.responseCode}.")
            }
            return conn.inputStream.use { it.lerComLimite(limite) }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.lerComLimite(limite: Long): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > limite) throw IOException("Resposta da fonte grande demais.")
            saida.write(buffer, 0, n)
        }
        return saida.toByteArray()
    }

    private fun pasta(context: Context, livro: LivroEncontrado): File =
        File(File(context.cacheDir, "livros_baixados"), livro.chave).apply { mkdirs() }

    /** Já há um arquivo deste livro baixado (cache do app)? Não cria pastas. */
    fun jaBaixado(context: Context, livro: LivroEncontrado): Boolean {
        val pasta = File(File(context.cacheDir, "livros_baixados"), livro.chave)
        return pasta.listFiles { f -> f.isFile && !f.name.endsWith(".part") && f.length() >= MIN_EPUB_BYTES }
            ?.isNotEmpty() == true
    }

    /** Baixa o EPUB (reaproveita um já baixado). Fica numa pasta própria do livro com o título como nome. */
    suspend fun baixar(context: Context, livro: LivroEncontrado, aoProgredir: (bytesNoTrecho: Long) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            if (livro.chave.startsWith("archive-")) return@withContext baixarDoArchive(context, livro, aoProgredir)
            val destino = File(pasta(context, livro), livro.nomeArquivo)
            if (ehEpubValido(destino)) return@withContext destino
            SupertonicAssetManager.downloadFileWithResume(livro.urlEpub, destino, USER_AGENT) { aoProgredir(it) }
            if (!ehEpubValido(destino)) {
                destino.delete()
                throw IOException("O arquivo baixado não é um EPUB válido.")
            }
            destino
        }

    /** Internet Archive: consulta o `metadata` do item, escolhe EPUB (ou texto) e baixa. */
    private suspend fun baixarDoArchive(context: Context, livro: LivroEncontrado, aoProgredir: (Long) -> Unit): File {
        val id = livro.chave.removePrefix("archive-")
        val arquivo = CatalogoArchive.escolherArquivo(obter(livro.urlEpub, MAX_METADATA_BYTES), id)
            ?: throw IOException("Este item não tem EPUB nem texto disponível para baixar.")
        val destino = File(pasta(context, livro), livro.nomeArquivo.removeSuffix(".epub") + "." + arquivo.extensao)
        val valido = if (arquivo.extensao == "epub") ehEpubValido(destino) else destino.isFile && destino.length() >= MIN_EPUB_BYTES
        if (valido) return destino
        SupertonicAssetManager.downloadFileWithResume(arquivo.url, destino, USER_AGENT) { aoProgredir(it) }
        val ok = if (arquivo.extensao == "epub") ehEpubValido(destino) else destino.isFile && destino.length() >= MIN_EPUB_BYTES
        if (!ok) {
            destino.delete()
            throw IOException("O arquivo baixado é inválido.")
        }
        return destino
    }

    /** EPUB é um zip: confere tamanho mínimo e a assinatura "PK". */
    private fun ehEpubValido(arquivo: File): Boolean {
        if (!arquivo.isFile || arquivo.length() < MIN_EPUB_BYTES) return false
        return arquivo.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
    }
}
