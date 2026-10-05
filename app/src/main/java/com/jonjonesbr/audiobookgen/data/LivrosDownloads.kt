package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.LivroEncontrado
import com.jonjonesbr.audiobookgen.domain.origemDaChave
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Baixa um livro de uma fonte legal e o guarda na biblioteca (usado pela fila de downloads, em segundo plano). */
object LivrosDownloads {
    suspend fun guardar(ctx: Context, livro: LivroEncontrado, progresso: ProgressoDownload) {
        if (BibliotecaStore.porChaveOrigem(ctx, livro.chave) != null) return
        var acumulado = 0L
        val baixado = LivrosClient.baixar(ctx, livro) { bytes ->
            acumulado += bytes
            progresso(livro.titulo, 0f, acumulado, 0L)
        }
        try {
            BibliotecaStore.importarArquivo(
                ctx, baixado, baixado.name, origemDaChave(livro.chave), livro.chave,
                tituloPreferido = livro.titulo, autorPreferido = livro.autor
            )
        } finally {
            withContext(Dispatchers.IO) { baixado.parentFile?.deleteRecursively() }
        }
    }
}
