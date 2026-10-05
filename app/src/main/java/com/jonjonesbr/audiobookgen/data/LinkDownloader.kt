package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.LinkDeLivro
import com.jonjonesbr.audiobookgen.domain.LinkException
import com.jonjonesbr.audiobookgen.domain.MotivoLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Baixa o arquivo de um link direto para o cache (Fase P-E). Segue redirecionamentos à mão (no máximo 6; nunca de https para
 * http), recusa páginas da web e arquivos acima de [LinkDeLivro.MAX_BYTES] e só aceita formatos de livro suportados.
 */
object LinkDownloader {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECIONAMENTOS = 6
    private const val USER_AGENT = "LylyReader/1 (leitor de audiobooks)"

    class Baixado(val arquivo: File, val nome: String)

    suspend fun baixar(ctx: Context, url: String, aoProgredir: (bytes: Long, total: Long) -> Unit): Baixado =
        withContext(Dispatchers.IO) {
            var atual = URL(url)
            var conn: HttpURLConnection? = null
            try {
                var tentativas = 0
                while (true) {
                    conn?.disconnect()
                    conn = (atual.openConnection() as HttpURLConnection).apply {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    val codigo = conn.responseCode
                    if (codigo in REDIRECIONAMENTOS) {
                        if (++tentativas > MAX_REDIRECIONAMENTOS) throw LinkException(MotivoLink.REDE, "Redirecionamentos demais")
                        val destino = conn.getHeaderField("Location") ?: throw LinkException(MotivoLink.REDE, "Redirecionamento sem destino")
                        val proximo = URL(atual, destino)
                        if (atual.protocol == "https" && proximo.protocol != "https") {
                            throw LinkException(MotivoLink.INVALIDO, "O link tentou sair de https para http")
                        }
                        if (proximo.protocol != "https" && proximo.protocol != "http") throw LinkException(MotivoLink.INVALIDO)
                        atual = proximo
                        continue
                    }
                    if (codigo != HttpURLConnection.HTTP_OK) throw LinkException(MotivoLink.REDE, "O servidor respondeu $codigo")
                    break
                }
                val resposta = conn!!
                val tipo = resposta.contentType
                if (LinkDeLivro.ehPaginaWeb(tipo)) throw LinkException(MotivoLink.PAGINA_WEB)
                val total = resposta.contentLengthLong
                if (total > LinkDeLivro.MAX_BYTES) throw LinkException(MotivoLink.GRANDE)
                val nome = LinkDeLivro.nomeComExtensao(
                    LinkDeLivro.nomeDoArquivo(atual.toString(), resposta.getHeaderField("Content-Disposition")), tipo
                ) ?: throw LinkException(MotivoLink.FORMATO)
                val saida = File.createTempFile("link_", ".tmp", ctx.cacheDir)
                try {
                    var lidos = 0L
                    resposta.inputStream.use { entrada ->
                        saida.outputStream().use { destino ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = entrada.read(buffer)
                                if (n < 0) break
                                lidos += n
                                if (lidos > LinkDeLivro.MAX_BYTES) throw LinkException(MotivoLink.GRANDE)
                                destino.write(buffer, 0, n)
                                aoProgredir(lidos, total)
                            }
                        }
                    }
                    if (lidos == 0L) throw LinkException(MotivoLink.REDE, "Arquivo vazio")
                    Baixado(saida, nome)
                } catch (e: Throwable) {
                    saida.delete()
                    throw if (e is IOException) LinkException(MotivoLink.REDE, e.message) else e
                }
            } finally {
                conn?.disconnect()
            }
        }

    private val REDIRECIONAMENTOS = setOf(
        HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308
    )
}
