package com.jonjonesbr.audiobookgen.data

import android.content.Context

/**
 * Liga o arquivo interno de um audiolivro (filesDir, onde a conversão grava) à cópia que o app exporta para
 * Downloads ou para a pasta escolhida. A Biblioteca lista a cópia; a tela do livro, o leitor e o mini-player
 * tocam o interno. Sem essa ligação, o cartão e o mini-player não reconhecem que é o mesmo áudio (P2).
 */
object CopiaExportadaStore {
    private const val PREFS = "copia_exportada"

    /** Guarda a uri da cópia exportada de [caminhoInterno]. Chamado logo depois de a exportação dar certo. */
    fun gravar(ctx: Context, caminhoInterno: String, uriExportada: String) {
        prefs(ctx).edit().putString(caminhoInterno, uriExportada).apply()
    }

    /** Uri da cópia exportada de [caminhoInterno]; null sem registro (conversão antiga ou sem exportação). */
    fun exportadaDe(ctx: Context, caminhoInterno: String): String? =
        prefs(ctx).getString(caminhoInterno, null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
