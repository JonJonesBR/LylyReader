package com.jonjonesbr.audiobookgen.domain

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.PlaybackProgressStore
import com.jonjonesbr.audiobookgen.ui.AudiobookItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Listagem de audiobooks convertidos (pasta SAF configurada ou MediaStore/legacy scan) —
 * extraído de [com.jonjonesbr.audiobookgen.ui.LibraryActivity] pra ser reusável a partir de
 * um Service (Android Auto), não só de uma Activity.
 */
object AudiobookLibraryUseCase {
    private const val TAG = "AudiobookLibraryUseCase"
    private const val MS_POR_SEGUNDO = 1000L

    suspend fun listar(context: Context): List<AudiobookItem> = withContext(Dispatchers.IO) {
        val tmp = mutableListOf<AudiobookItem>()
        val pastaStr = AppPrefs(context).pastaDestino
        if (pastaStr != null) {
            val uri = Uri.parse(pastaStr)
            val perms = context.contentResolver.persistedUriPermissions
            if (perms.any { it.uri == uri && it.isReadPermission }) {
                carregarDoPastaSaf(context, uri, tmp)
            } else {
                carregarDoDestinoPadrao(context, tmp)
            }
        } else {
            carregarDoDestinoPadrao(context, tmp)
        }
        tmp
    }

    private fun carregarDoDestinoPadrao(context: Context, dest: MutableList<AudiobookItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            carregarDoMediaStore(context, dest)
        } else {
            carregarDoPastaLegacy(context, dest)
        }
    }

    // query()/getColumnIndexOrThrow() lançam tipos heterogêneos (SecurityException,
    // IllegalArgumentException) — best-effort, mesmo padrão já aceito no projeto (T1.5).
    //
    // Usa a coleção MediaStore.Audio (não MediaStore.Downloads): confirmado em teste real de
    // dispositivo (Android 16) que a coleção Downloads esconde arquivos de outros donos (ou
    // órfãos, OWNER_PACKAGE_NAME nulo após reinstalação) mesmo com READ_MEDIA_AUDIO concedido —
    // é uma particularidade do MediaProvider, não um filtro desta query. READ_MEDIA_AUDIO
    // garante visibilidade irrestrita só na coleção Audio, onde o mesmo arquivo .mp3 também é
    // indexado.
    @Suppress("TooGenericExceptionCaught")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun carregarDoMediaStore(context: Context, dest: MutableList<AudiobookItem>) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        // MIME_TYPE aceita audio/mpeg E audio/x-wav|audio/wav: sem FFmpeg no device (nenhum
        // Android real tem o binário em PATH), o fallback Python puro salva WAV (Kokoro/
        // Supertonic sintetizam WAV) com extensão .mp3 — o MediaStore indexa pelo conteúdo
        // real (sniff), não pela extensão, então esses itens ficavam INVISÍVEIS aqui mesmo
        // tocando normalmente (MediaPlayer lê pelos magic bytes). Confirmado em teste real
        // de device (Android 16): arquivo gerado pelo Kokoro indexado como audio/x-wav.
        val selection = "${MediaStore.Audio.Media.MIME_TYPE} IN (?, ?, ?) AND " +
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(
            "audio/mpeg", "audio/x-wav", "audio/wav", "${Environment.DIRECTORY_DOWNLOADS}/"
        )

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id      = cursor.getLong(idCol)
                    val nome    = cursor.getString(nameCol) ?: continue
                    val tamanho = cursor.getLong(sizeCol)
                    val data    = cursor.getLong(dateCol) * MS_POR_SEGUNDO
                    val uri     = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val duracao = obterDuracao(context, uri, null)

                    dest.add(AudiobookItem(
                        nome          = nome.removeSuffix(".mp3"),
                        uri           = uri,
                        caminho       = null,
                        tamanhoBytes  = tamanho,
                        dataAdicionado = data,
                        duracaoMs     = duracao,
                        localizacao   = context.getString(R.string.folder_downloads),
                        progressoPct  = PlaybackProgressStore.percent(context, uri.toString())
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar MediaStore: ${e.message}")
        }
    }

    private fun carregarDoPastaLegacy(context: Context, dest: MutableList<AudiobookItem>) {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.listFiles { f -> f.extension.lowercase() == "mp3" }?.forEach { file ->
            val duracao = obterDuracao(context, null, file.absolutePath)
            val fileUri = Uri.fromFile(file)
            dest.add(AudiobookItem(
                nome           = file.nameWithoutExtension,
                uri            = fileUri,
                caminho        = file.absolutePath,
                tamanhoBytes   = file.length(),
                dataAdicionado = file.lastModified(),
                duracaoMs      = duracao,
                localizacao    = context.getString(R.string.folder_downloads),
                progressoPct   = PlaybackProgressStore.percent(context, fileUri.toString())
            ))
        }
    }

    // query()/getColumnIndexOrThrow() lançam tipos heterogêneos (SecurityException,
    // IllegalArgumentException) — best-effort, mesmo padrão já aceito no projeto (T1.5).
    @Suppress("TooGenericExceptionCaught")
    private fun carregarDoPastaSaf(context: Context, folderUri: Uri, dest: MutableList<AudiobookItem>) {
        try {
            val treeId      = DocumentsContract.getTreeDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeId)
            val projection  = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val nomePasta = resolverNomePastaSaf(context, folderUri)

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val colunas = ColunasSaf(
                    idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE),
                    dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                )
                while (cursor.moveToNext()) {
                    val item = itemDeCursorSaf(context, cursor, colunas, folderUri, nomePasta) ?: continue
                    dest.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao listar pasta SAF: ${e.message}")
        }
    }

    private data class ColunasSaf(
        val idCol: Int,
        val nameCol: Int,
        val sizeCol: Int,
        val dateCol: Int,
        val mimeCol: Int
    )

    /** Monta um [AudiobookItem] a partir da linha atual do cursor SAF, ou null se não for um MP3 válido. */
    private fun itemDeCursorSaf(
        context: Context,
        cursor: android.database.Cursor,
        colunas: ColunasSaf,
        folderUri: Uri,
        nomePasta: String
    ): AudiobookItem? {
        val mime = cursor.getString(colunas.mimeCol)
        val docId = cursor.getString(colunas.idCol)
        val nome = cursor.getString(colunas.nameCol)
        if (mime != "audio/mpeg" || docId == null || nome == null) return null

        val tamanho = cursor.getLong(colunas.sizeCol)
        val data = cursor.getLong(colunas.dateCol)
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
        val duracao = obterDuracao(context, fileUri, null)

        return AudiobookItem(
            nome           = nome.removeSuffix(".mp3"),
            uri            = fileUri,
            caminho        = null,
            tamanhoBytes   = tamanho,
            dataAdicionado = data,
            duracaoMs      = duracao,
            localizacao    = nomePasta,
            progressoPct   = PlaybackProgressStore.percent(context, fileUri.toString())
        )
    }

    private fun obterDuracao(context: Context, uri: Uri?, caminho: String?): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri != null) retriever.setDataSource(context, uri)
            else if (caminho != null) retriever.setDataSource(caminho)
            else return 0L
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
