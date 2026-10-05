package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ChavesDeLivro
import com.jonjonesbr.audiobookgen.domain.SpeakerAiStore
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import com.jonjonesbr.audiobookgen.domain.SpeakerEditsStore
import com.jonjonesbr.audiobookgen.service.GuidedBookAudioStore
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import java.io.File

/**
 * Tudo que o app guarda de um livro de TEXTO usa o caminho do arquivo como chave (posição de leitura, voz por livro,
 * marcadores, personagens…). Aqui ficam as duas operações que mexem em todos de uma vez:
 * [migrar] (o livro mudou de caminho, ex.: foi do cache para a biblioteca) e [remover] (o livro foi apagado).
 *
 * O MP3 gerado do livro e o progresso dele (`PlaybackProgressStore`, `ChapterMarksStore`) NÃO são tocados.
 */
object DadosDoLivro {
    private const val PREFIXO_SCROLL = "reader_scroll_"
    private const val PREFIXO_TOTAL = "reader_total_"

    private fun pastaPersonagens(ctx: Context) = File(ctx.filesDir, "speaker_attribution")

    /** Copia os dados de [antigo] para [novo]; **nunca sobrescreve** o que o caminho novo já tem (a posição de leitura fica com a maior). */
    fun migrar(ctx: Context, antigo: String, novo: String) {
        if (antigo == novo || antigo.isBlank() || novo.isBlank()) return
        BookmarkStore.renomearLivro(ctx, antigo, novo)
        HighlightStore.renomearLivro(ctx, antigo, novo)
        GuidedBookPrefsStore.renomear(ctx, antigo, novo)
        GuidedBookAudioStore.renomear(ctx, antigo, novo)
        LinkedBookStore.renomearTexto(ctx, antigo, novo)
        RecentReadingsStore.renomear(ctx, antigo, novo)
        StatsStore.renomearLivroConcluido(ctx, antigo, novo)

        val prefs = ctx.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE)
        migrarInteiro(prefs, PREFIXO_SCROLL, antigo, novo, ChavesDeLivro.maiorInteiro)
        migrarInteiro(prefs, PREFIXO_TOTAL, antigo, novo, ChavesDeLivro.maiorInteiro)

        val pasta = pastaPersonagens(ctx)
        SpeakerAttributionStore(pasta).migrarIdentidade(antigo, novo)
        SpeakerAiStore(pasta).migrarIdentidade(antigo, novo)
        SpeakerEditsStore(pasta).migrarIdentidade(antigo, novo)
    }

    private fun migrarInteiro(
        prefs: android.content.SharedPreferences,
        prefixo: String,
        antigo: String,
        novo: String,
        resolver: (Any?, Any?) -> Any?
    ) {
        val todos = prefs.all
        val resultado = ChavesDeLivro.migrarMapa(todos, antigo, novo, prefixo, resolver)[prefixo + novo] as? Int ?: return
        if (todos[prefixo + novo] != resultado) prefs.edit().putInt(prefixo + novo, resultado).apply()
    }

    /** Apaga os dados do livro de [caminho] (exceto o histórico de "livros concluídos" e o MP3). */
    fun remover(ctx: Context, caminho: String) {
        if (caminho.isBlank()) return
        BookmarkStore.removerDoLivro(ctx, caminho)
        HighlightStore.removerDoLivro(ctx, caminho)
        GuidedBookPrefsStore.remover(ctx, caminho)
        GuidedBookAudioStore.remover(ctx, caminho)
        LinkedBookStore.removerPorTexto(ctx, caminho)
        RecentReadingsStore.remove(ctx, caminho)
        ctx.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE).edit()
            .remove(PREFIXO_SCROLL + caminho).remove(PREFIXO_TOTAL + caminho).apply()
        val pasta = pastaPersonagens(ctx)
        SpeakerAttributionStore(pasta).apagarIdentidade(caminho)
        SpeakerAiStore(pasta).clear(caminho)
        SpeakerEditsStore(pasta).apagarIdentidade(caminho)
    }
}
