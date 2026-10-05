package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.EventoAprendizado
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Livro de exemplo da Fase J: "Um Apólogo", de Machado de Assis (domínio público), embutido em `assets/exemplo/`.
 * Serve de prática nas lições de "Aprender o app". Importar de novo é seguro: o id da biblioteca é o hash do conteúdo.
 */
object LivroDeExemplo {
    private const val ASSET = "exemplo/um_apologo.txt"
    private const val TITULO = "Um Apólogo"
    private const val AUTOR = "Machado de Assis"

    suspend fun garantir(ctx: Context): LivroBiblioteca {
        val app = ctx.applicationContext
        // Importar o exemplo não pode concluir sozinho a lição "Adicionar um livro".
        val jaTinhaEvento = EventoAprendizado.LIVRO_IMPORTADO.id in AppPrefs(app).eventosAprendizado
        val livro = withContext(Dispatchers.IO) {
            val temp = File(app.cacheDir, "um_apologo.txt")
            app.assets.open(ASSET).use { entrada -> temp.outputStream().use { entrada.copyTo(it) } }
            try {
                BibliotecaStore.importarArquivo(app, temp, "Um Apologo.txt", OrigemLivro.ARQUIVO, null, TITULO, AUTOR)
            } finally {
                temp.delete()
            }
        }
        AppPrefs(app).livroExemploId = livro.id
        if (!jaTinhaEvento) AppPrefs(app).removerEvento(EventoAprendizado.LIVRO_IMPORTADO.id)
        return livro
    }
}
