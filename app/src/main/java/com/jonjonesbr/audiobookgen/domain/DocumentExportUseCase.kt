package com.jonjonesbr.audiobookgen.domain

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ExportResult(val destUri: Uri, val nomePasta: String)

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

    suspend fun exportar(mp3: File, pastaSafUri: Uri?, mimeType: String = "audio/mpeg"): Result<ExportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val destUri =
                    if (pastaSafUri != null) {
                        copiarParaSafFolder(mp3, pastaSafUri, mimeType)
                            ?: throw IOException("Falha ao criar arquivo na pasta SAF")
                    } else {
                        copiarParaDownloads(mp3, mimeType)
                            ?: throw IOException("Falha ao copiar para pasta Downloads")
                    }
                val nomePasta =
                    if (pastaSafUri != null) resolverNomePastaSaf(context, pastaSafUri) else "Downloads"
                ExportResult(destUri, nomePasta)
            }
        }

    private fun copiarParaDownloads(mp3: File, mimeType: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copiarParaDownloadsViaMediaStore(mp3, mimeType)
        } else {
            copiarParaDownloadsLegado(mp3)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copiarParaDownloadsViaMediaStore(mp3: File, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, mp3.name)
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
    private fun copiarParaDownloadsLegado(mp3: File): Uri {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadDir.mkdirs()
        val dest = File(downloadDir, mp3.name)
        mp3.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }

    private fun copiarParaSafFolder(mp3: File, folderUri: Uri, mimeType: String): Uri? {
        val docUri =
            DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )
        val fileUri =
            DocumentsContract.createDocument(contentResolver, docUri, mimeType, mp3.name)
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
                ?: throw IOException("Falha ao criar a pasta '$nomeSubpasta'")
            val sucessos = arquivos.count { (nomeArquivo, conteudo) ->
                gravarTextoNaPasta(pastaDestino, nomeArquivo, conteudo)
            }
            if (sucessos < arquivos.size) {
                throw IOException("Falha ao exportar ${arquivos.size - sucessos} de ${arquivos.size} capítulos")
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
