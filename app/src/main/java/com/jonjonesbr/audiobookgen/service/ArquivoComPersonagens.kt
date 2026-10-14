package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ConversaoComPersonagensRegras
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import java.io.File

/**
 * Prepara a conversão de um livro inteiro pela fila/biblioteca com o mapa de personagens: grava o texto reconstruído do mapa
 * em `filesDir` (não em cache, para a conversão pausada achar o arquivo ao ser retomada) e devolve o par (txt, mapa).
 */
object ArquivoComPersonagens {

    private fun pastaDoMapa(context: Context) = File(context.filesDir, "speaker_attribution")

    /** Pasta do .txt reconstruído do livro: uma por livro, com o mesmo sha usado para o mapa dele. */
    private fun pastaDoTexto(context: Context, caminhoDoLivro: String): File? =
        SpeakerAttributionStore(pastaDoMapa(context)).fileForBook(caminhoDoLivro)
            ?.let { File(context.filesDir, "texto_com_personagens/${it.nameWithoutExtension}") }

    /**
     * Par (caminho do .txt, caminho do mapa), ou null para converter o arquivo original como antes.
     * Faz I/O: chamar fora da thread principal.
     */
    fun preparar(context: Context, caminhoDoLivro: String): Pair<String, String>? {
        val store = SpeakerAttributionStore(pastaDoMapa(context))
        if (!store.temVozesAtribuidas(caminhoDoLivro)) return null
        val texto = store.textoDoMapa(caminhoDoLivro) ?: return null
        val extensao = File(caminhoDoLivro).extension
        // Capítulos do mapa só são lidos quando o formato depende deles (epub/txt/md).
        val mapaTemCapitulos = ConversaoComPersonagensRegras.formatoTemCapitulosNaExtracao(extensao) &&
            store.temCapitulos(caminhoDoLivro)
        if (!ConversaoComPersonagensRegras.usarMapa(true, true, mapaTemCapitulos, extensao)) return null
        val mapa = store.fileForBook(caminhoDoLivro) ?: return null
        val pasta = pastaDoTexto(context, caminhoDoLivro) ?: return null
        // Nome do livro no .txt: o worker usa o nome do arquivo como título da conversão.
        val txt = File(pasta, "${File(caminhoDoLivro).nameWithoutExtension}.txt")
        return runCatching {
            pasta.mkdirs()
            txt.writeText(texto, Charsets.UTF_8)
            txt.absolutePath to mapa.absolutePath
        }.getOrNull()
    }

    /**
     * Apaga o .txt reconstruído do livro (livro removido ou migrado de caminho). NÃO chamar ao fim da conversão:
     * a retomada de uma conversão pausada precisa desse arquivo.
     */
    fun apagar(context: Context, caminhoDoLivro: String) {
        pastaDoTexto(context, caminhoDoLivro)?.deleteRecursively()
    }
}
