package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.net.Uri
import com.jonjonesbr.audiobookgen.domain.ImportedFileNamePolicy
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import com.jonjonesbr.audiobookgen.domain.idDoConteudo
import com.jonjonesbr.audiobookgen.domain.lerMetadadosEpub
import com.jonjonesbr.audiobookgen.domain.nomeExibicaoDoArquivo
import com.jonjonesbr.audiobookgen.domain.nomeSeguroNaPasta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Biblioteca persistente de textos: `filesDir/livros/<id>/` com o arquivo do livro, `livro.json` e `capa.jpg`
 * (se houver). O `id` vem do conteúdo (SHA-256), então reimportar o MESMO arquivo devolve o mesmo caminho — e
 * tudo que é indexado pelo caminho (posição, personagens, marcadores…) sobrevive à reimportação.
 *
 * Sem Room (nenhuma migração de schema): um JSON por livro. Toda função de disco roda em [Dispatchers.IO].
 */
object BibliotecaStore {
    private const val ARQUIVO_JSON = "livro.json"
    private const val ARQUIVO_CAPA = "capa.jpg"
    private const val BUFFER = 64 * 1024

    private val trava = Mutex()

    fun dir(ctx: Context): File = File(ctx.filesDir, "livros")

    private fun pastaDo(ctx: Context, id: String) = File(dir(ctx), id)

    fun arquivoDo(ctx: Context, livro: LivroBiblioteca): File = File(pastaDo(ctx, livro.id), livro.nomeArquivo)

    fun capaDo(ctx: Context, livro: LivroBiblioteca): File? =
        if (!livro.temCapa) null else File(pastaDo(ctx, livro.id), ARQUIVO_CAPA).takeIf { it.isFile }

    /** Copia o conteúdo de [uri] para a biblioteca (dedupe por conteúdo). Não altera o original. */
    suspend fun importarDeUri(
        ctx: Context,
        uri: Uri,
        nomeOriginal: String,
        origem: OrigemLivro = OrigemLivro.ARQUIVO,
        chaveOrigem: String? = null
    ): LivroBiblioteca = importar(
        ctx, { ctx.contentResolver.openInputStream(uri) }, nomeOriginal, ctx.contentResolver.getType(uri),
        origem, chaveOrigem, null, null
    )

    /**
     * Mesmo fluxo a partir de um [File] (usado pela busca de livros). **Não apaga a origem**: quem chama decide.
     * [tituloPreferido]/[autorPreferido] só valem quando o EPUB não traz título/autor.
     */
    suspend fun importarArquivo(
        ctx: Context,
        arquivo: File,
        nomeOriginal: String,
        origem: OrigemLivro,
        chaveOrigem: String?,
        tituloPreferido: String? = null,
        autorPreferido: String? = null
    ): LivroBiblioteca = importar(
        ctx, { arquivo.inputStream() }, nomeOriginal, null, origem, chaveOrigem, tituloPreferido, autorPreferido
    )

    private suspend fun importar(
        ctx: Context,
        abrir: () -> InputStream?,
        nomeOriginal: String,
        mime: String?,
        origem: OrigemLivro,
        chaveOrigem: String?,
        tituloPreferido: String?,
        autorPreferido: String?
    ): LivroBiblioteca = withContext(Dispatchers.IO) {
        val raiz = dir(ctx).apply { mkdirs() }
        val temporario = File(raiz, ".importando-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            (abrir() ?: throw IOException("Não foi possível abrir o arquivo.")).use { entrada ->
                DigestInputStream(entrada, digest).use { lendo ->
                    temporario.outputStream().use { saida ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = lendo.read(buffer)
                            if (n < 0) break
                            saida.write(buffer, 0, n)
                            bytes += n
                        }
                    }
                }
            }
            if (bytes == 0L) throw IOException("O arquivo está vazio.")
            val id = idDoConteudo(digest.digest().joinToString("") { "%02x".format(it) })

            trava.withLock {
                val pasta = pastaDo(ctx, id)
                lerLivro(pasta)?.takeIf { File(pasta, it.nomeArquivo).isFile }?.let { return@withLock it }

                pasta.deleteRecursively() // resto de importação interrompida
                if (!pasta.mkdirs()) throw IOException("Não foi possível criar a pasta do livro.")
                val nome = nomeSeguroNaPasta(ImportedFileNamePolicy.safeCacheName(nomeOriginal, "livro", mime))
                val destino = File(pasta, nome)
                if (!temporario.renameTo(destino)) {
                    temporario.copyTo(destino, overwrite = true)
                    temporario.delete()
                }
                val formato = nome.substringAfterLast('.', "").lowercase()
                val meta = if (formato == "epub") lerMetadadosEpub(destino) else null
                val temCapa = meta?.capa != null
                if (temCapa) File(pasta, ARQUIVO_CAPA).writeBytes(meta!!.capa!!)
                val livro = LivroBiblioteca(
                    id = id,
                    titulo = meta?.titulo ?: tituloPreferido?.ifBlank { null } ?: nomeExibicaoDoArquivo(nomeOriginal.ifBlank { nome }),
                    autor = meta?.autor ?: autorPreferido?.ifBlank { null },
                    origem = origem,
                    chaveOrigem = chaveOrigem,
                    formato = formato,
                    nomeArquivo = nome,
                    tamanhoBytes = destino.length(),
                    adicionadoEm = System.currentTimeMillis(),
                    abertoEm = null,
                    temCapa = temCapa
                )
                gravarLivro(pasta, livro)
                AppPrefs(ctx).registrarEvento(com.jonjonesbr.audiobookgen.domain.EventoAprendizado.LIVRO_IMPORTADO.id)
                livro
            }
        } finally {
            temporario.delete()
        }
    }

    private fun lerLivro(pasta: File): LivroBiblioteca? {
        val json = File(pasta, ARQUIVO_JSON)
        if (!json.isFile) return null
        return runCatching { LivroBiblioteca.fromJson(json.readText()) }.getOrNull()
    }

    /** Grava por arquivo temporário + renomeação, para nunca deixar um JSON pela metade. */
    private fun gravarLivro(pasta: File, livro: LivroBiblioteca) {
        val tmp = File(pasta, "$ARQUIVO_JSON.tmp")
        tmp.writeText(livro.toJson())
        val alvo = File(pasta, ARQUIVO_JSON)
        if (!tmp.renameTo(alvo)) {
            alvo.writeText(livro.toJson())
            tmp.delete()
        }
    }

    /** Livros válidos (pasta com `livro.json` legível e o arquivo do livro presente). */
    suspend fun listar(ctx: Context): List<LivroBiblioteca> = withContext(Dispatchers.IO) {
        listarSincrono(ctx)
    }

    private fun listarSincrono(ctx: Context): List<LivroBiblioteca> =
        dir(ctx).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { pasta ->
            lerLivro(pasta)?.takeIf { it.id == pasta.name && File(pasta, it.nomeArquivo).isFile }
        }

    /** Título (da biblioteca) do livro dono deste caminho, ou null se o caminho não é de um livro da biblioteca. Leitura rápida de um arquivo pequeno. */
    fun tituloPorCaminho(ctx: Context, caminho: String): String? = porCaminhoSincrono(ctx, caminho)?.titulo

    /** O livro dono deste caminho (`.../livros/<id>/<arquivo>`), ou null se o caminho não é da biblioteca. */
    suspend fun porCaminho(ctx: Context, caminho: String): LivroBiblioteca? = withContext(Dispatchers.IO) {
        porCaminhoSincrono(ctx, caminho)
    }

    private fun porCaminhoSincrono(ctx: Context, caminho: String): LivroBiblioteca? {
        val pasta = File(caminho).parentFile ?: return null
        val raiz = dir(ctx)
        if (pasta.parentFile?.canonicalPath != raiz.canonicalPath) return null
        return lerLivro(pasta)?.takeIf { it.id == pasta.name }
    }

    suspend fun porChaveOrigem(ctx: Context, chave: String): LivroBiblioteca? = withContext(Dispatchers.IO) {
        listarSincrono(ctx).firstOrNull { it.chaveOrigem == chave }
    }

    /** Atualiza `abertoEm`; ignora caminhos fora da biblioteca. */
    suspend fun marcarAberto(ctx: Context, caminho: String) = withContext(Dispatchers.IO) {
        trava.withLock {
            val livro = porCaminhoSincrono(ctx, caminho) ?: return@withLock
            gravarLivro(pastaDo(ctx, livro.id), livro.copy(abertoEm = System.currentTimeMillis()))
        }
    }

    /**
     * Apaga a pasta do livro. **Não apaga o MP3** vinculado (continua na biblioteca de audiobooks).
     * Também limpa o que é indexado pelo caminho do texto (posição, marcadores, personagens…) — ver [DadosDoLivro].
     */
    suspend fun apagar(ctx: Context, livro: LivroBiblioteca) = withContext(Dispatchers.IO) {
        val caminho = arquivoDo(ctx, livro).absolutePath
        trava.withLock { pastaDo(ctx, livro.id).deleteRecursively() }
        DadosDoLivro.remover(ctx, caminho)
        Unit
    }

    /** Fração lida (posição de rolagem ÷ total de parágrafos gravados pelo leitor); null se o leitor ainda não gravou o total. */
    fun progressoDe(ctx: Context, livro: LivroBiblioteca): Float? {
        val prefs = ctx.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE)
        val caminho = arquivoDo(ctx, livro).absolutePath
        val total = prefs.getInt("reader_total_$caminho", 0)
        if (total <= 0) return null
        return (prefs.getInt("reader_scroll_$caminho", 0).toFloat() / total).coerceIn(0f, 1f)
    }

    /** O livro já tem audiobook gerado (e o arquivo ainda existe)? */
    fun temAudio(ctx: Context, livro: LivroBiblioteca): Boolean {
        val mp3 = LinkedBookStore.mp3Vinculado(ctx, arquivoDo(ctx, livro).absolutePath) ?: return false
        return mp3.startsWith("content:") || File(mp3).exists()
    }

    fun espacoUsado(ctx: Context): Long =
        dir(ctx).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
