package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * Modelo de dados limpo para trafegar informações de arquivos/pastas do SAF para a ViewModel.
 */
data class DocumentMetadata(
    val uri: Uri,
    val nome: String,
    val tamanhoBytes: Long,
    val isPasta: Boolean = false
)

/**
 * Repositório responsável por conversar com o ContentResolver e o Storage Access Framework (SAF).
 * Isola a lógica de extração de permissões permanentes e leitura de metadados da UI.
 */
class DocumentRepository(private val context: Context) {

    private val TAG = "DocumentRepository"
    private val contentResolver = context.contentResolver

    /**
     * Processa a seleção de uma pasta de destino via SAF (OpenDocumentTree),
     * extraindo a permissão persistente imediatamente e recuperando seu nome.
     */
    fun processarPastaSaf(uri: Uri): DocumentMetadata {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro ao requisitar permissão persistente em pasta: ${e.message}")
        }
        
        val nome = extrairNomeDePasta(uri)
        
        return DocumentMetadata(
            uri = uri,
            nome = nome,
            tamanhoBytes = 0L,
            isPasta = true
        )
    }

    /**
     * Extrai os metadados brutos (nome do arquivo e tamanho) de uma URI selecionada via SAF.
     */
    fun extrairMetadadosArquivo(uri: Uri): DocumentMetadata {
        var nome = "arquivo_desconhecido"
        var tamanho = 0L

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idxNome = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idxNome >= 0) nome = cursor.getString(idxNome)

                    val idxTamanho = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idxTamanho >= 0) {
                        // Verifica se o valor de tamanho não está nulo no cursor
                        if (!cursor.isNull(idxTamanho)) {
                            tamanho = cursor.getLong(idxTamanho)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar metadados do documento: ${e.message}")
        }

        return DocumentMetadata(
            uri = uri,
            nome = nome,
            tamanhoBytes = tamanho,
            isPasta = false
        )
    }

    /**
     * Limpa o prefixo primário retornado pela URI do Storage para pegar apenas a pasta visível.
     */
    private fun extrairNomeDePasta(uri: Uri): String {
        return try {
            val path = uri.lastPathSegment ?: return "Pasta personalizada"
            val partes = path.split(":")
            if (partes.size >= 2) partes.last().split("/").last() else "Pasta personalizada"
        } catch (_: Exception) {
            "Pasta personalizada"
        }
    }
}
