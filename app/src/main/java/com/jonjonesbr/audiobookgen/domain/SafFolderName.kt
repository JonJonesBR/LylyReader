package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.net.Uri
import com.jonjonesbr.audiobookgen.R

/** Nome de exibicao de uma pasta a partir da Uri SAF (arvore de documentos) — extrai o ultimo
 * segmento do path apos os dois pontos, ou usa um nome generico se nao conseguir. */
fun resolverNomePastaSaf(context: Context, uri: Uri): String {
    return try {
        val path = uri.lastPathSegment ?: return context.getString(R.string.folder_custom)
        val partes = path.split(":")
        if (partes.size >= 2) partes.last().split("/").last()
        else context.getString(R.string.folder_custom)
    } catch (_: Exception) {
        context.getString(R.string.folder_custom)
    }
}
