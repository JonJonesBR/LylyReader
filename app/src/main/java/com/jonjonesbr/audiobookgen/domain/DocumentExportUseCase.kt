package com.jonjonesbr.audiobookgen.domain

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.jonjonesbr.audiobookgen.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ExportResult(val destUri: Uri, val nomePasta: String)

/**
 * MIME do áudio exportado pelos primeiros bytes: contêiner MP4 (cabeçalho "ftyp" nos bytes 4..8) é audio/mp4, o resto
 * (MP3 de verdade ou WAV com nome .mp3) segue audio/mpeg, como antes.
 */
internal fun mimeDoAudioExportado(primeirosBytes: ByteArray): String =
    if (primeirosBytes.size >= 8 && String(primeirosBytes, 4, 4, Charsets.US_ASCII) == "ftyp") MIME_M4A else MIME_MP3

internal const val MIME_M4A = "audio/mp4"
internal const val MIME_MP3 = "audio/mpeg"

/**
 * Nome do arquivo exportado. Com MIME audio/mp4 (AAC) a extensão vira `.m4a`, para o MediaStore não gerar
 * "livro.mp3.m4a". Outros MIMEs mantêm o nome original.
 */
internal fun nomeDoArquivoExportado(nome: String, mime: String): String {
    if (mime != MIME_M4A) return nome
    val base = nome.replace(Regex("\\.mp3$", RegexOption.IGNORE_CASE), "").ifEmpty { "audiobook" }
    return "$base.m4a"
}

/** Nome sem a extensão de áudio do audiolivro (.mp3 ou .m4a), para mostrar na biblioteca. */
internal fun semExtensaoDeAudio(nome: String): String = nome.removeSuffix(".mp3").removeSuffix(".m4a")

private fun lerInicioDoArquivo(arquivo: File): ByteArray {
    val inicio = ByteArray(12)
    val lidos = runCatching { arquivo.inputStream().use { it.read(inicio) } }.getOrDefault(0)
    return if (lidos > 0) inicio.copyOf(lidos) else ByteArray(0)
}

sealed class ExportStatus {
    data class Sucesso(val result: ExportResult) : ExportStatus()
    data class Erro(val mensagem: String) : ExportStatus()
}

/**
 * UseCase responsável por toda a lógica de IO de exportação do MP3 gerado.
 * Isola MediaStore (Downloads) e operações SAF da camada de apresentação.
 * Sempre executa em Dispatchers.IO e retorna Result para tratamento de erros na ViewModel.
 */
class DocumentExportUseCase(private val context: Context) {

    private val contentResolver = context.contentResolver

    /** [mimeType] ausente: MIME pelo conteúdo do arquivo (MP4/AAC vira audio/mp4; o resto segue audio/mpeg). */
    suspend fun exportar(mp3: File, pastaSafUri: Uri?, mimeType: String? = null): Result<ExportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mime = mimeType ?: mimeDoAudioExportado(lerInicioDoArquivo(mp3))
                val nomeDestino = nomeDoArquivoExportado(mp3.name, mime)
                val destUri =
                    if (pastaSafUri != null) {
                        copiarParaSafFolder(mp3, nomeDestino, pastaSafUri, mime)
                            ?: throw IOException(context.getString(R.string.export_falha_criar_saf))
                    } else {
                        copiarParaDownloads(mp3, nomeDestino, mime)
                            ?: throw IOException(context.getString(R.string.export_falha_copiar_downloads))
                    }
                val nomePasta =
                    if (pastaSafUri != null) resolverNomePastaSaf(context, pastaSafUri) else "Downloads"
                ExportResult(destUri, nomePasta)
            }
        }

    private fun copiarParaDownloads(mp3: File, nomeDestino: String, mimeType: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copiarParaDownloadsViaMediaStore(mp3, nomeDestino, mimeType)
        } else {
            copiarParaDownloadsLegado(mp3, nomeDestino)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copiarParaDownloadsViaMediaStore(mp3: File, nomeDestino: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nomeDestino)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = contentResolver.openOutputStream(uri)
        if (out != null) {
            try {
                gravarAudioEPublicar(uri, out, mp3)
            } catch (e: IOException) {
                // Falhou no meio da cópia — remove a linha pendente em vez de deixar um
                // audiobook fantasma (0 bytes) na biblioteca.
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            contentResolver.delete(uri, null, null)
        }
        return if (out != null) uri else null
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun gravarAudioEPublicar(uri: Uri, out: java.io.OutputStream, mp3: File) {
        out.use { o -> mp3.inputStream().use { it.copyTo(o) } }
        // Só publica o item (IS_PENDING = 0) depois da cópia confirmada.
        val update = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, update, null, null)
    }

    @Suppress("DEPRECATION")
    private fun copiarParaDownloadsLegado(mp3: File, nomeDestino: String): Uri {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadDir.mkdirs()
        val dest = File(downloadDir, nomeDestino)
        mp3.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    private fun copiarParaSafFolder(mp3: File, nomeDestino: String, folderUri: Uri, mimeType: String): Uri? {
        val docUri =
            DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )
        val fileUri =
            DocumentsContract.createDocument(contentResolver, docUri, mimeType, nomeDestino)
                ?: return null
        val out = contentResolver.openOutputStream(fileUri)
        if (out != null) out.use { o -> mp3.inputStream().use { it.copyTo(o) } }
        return if (out != null) fileUri else null
    }

    /**
     * Exporta vários arquivos de texto (ex.: um por capítulo) dentro de uma subpasta nova
     * criada em [pastaSafUri]. Retorna quantos arquivos foram gravados com sucesso.
     */
    suspend fun exportarTextos(
        nomeSubpasta: String,
        arquivos: List<Pair<String, String>>,
        pastaSafUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val pastaDestino = criarSubpastaSaf(pastaSafUri, nomeSubpasta)
                ?: throw IOException(context.getString(R.string.export_falha_criar_pasta, nomeSubpasta))
            val sucessos = arquivos.count { (nomeArquivo, conteudo) ->
                gravarTextoNaPasta(pastaDestino, nomeArquivo, conteudo)
            }
            if (sucessos < arquivos.size) {
                throw IOException(context.getString(R.string.export_falha_capitulos, arquivos.size - sucessos, arquivos.size))
            }
            sucessos
        }
    }

    private fun gravarTextoNaPasta(pastaDestino: Uri, nomeArquivo: String, conteudo: String): Boolean {
        val fileUri = DocumentsContract.createDocument(contentResolver, pastaDestino, "text/plain", nomeArquivo)
            ?: return false
        val out = contentResolver.openOutputStream(fileUri)
        if (out != null) out.bufferedWriter().use { it.write(conteudo) }
        return out != null
    }

    private fun criarSubpastaSaf(pastaUri: Uri, nomePasta: String): Uri? {
        val parentDocUri =
            DocumentsContract.buildDocumentUriUsingTree(pastaUri, DocumentsContract.getTreeDocumentId(pastaUri))
        return DocumentsContract.createDocument(
            contentResolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, nomePasta
        )
    }
}
