package com.jonjonesbr.audiobookgen.data

import android.content.Context

/**
 * Vincula o caminho do livro-texto ao MP3 gerado por uma conversão completa dele, nas duas
 * direções — usado pela ponte de UI entre leitor e player (Fase 5, item 2: só o link entre
 * os dois modos, sem sincronizar posição automaticamente).
 */
object LinkedBookStore {
    private const val PREFS = "linked_books"
    private const val PREFIXO_TEXTO_PARA_MP3 = "texto:"
    private const val PREFIXO_MP3_PARA_TEXTO = "mp3:"

    fun vincular(ctx: Context, caminhoTexto: String, caminhoMp3: String) {
        prefs(ctx).edit()
            .putString(PREFIXO_TEXTO_PARA_MP3 + caminhoTexto, caminhoMp3)
            .putString(PREFIXO_MP3_PARA_TEXTO + caminhoMp3, caminhoTexto)
            .apply()
    }

    fun mp3Vinculado(ctx: Context, caminhoTexto: String): String? =
        prefs(ctx).getString(PREFIXO_TEXTO_PARA_MP3 + caminhoTexto, null)

    fun textoVinculado(ctx: Context, caminhoMp3: String): String? =
        prefs(ctx).getString(PREFIXO_MP3_PARA_TEXTO + caminhoMp3, null)

    /** Remove o vínculo (nas duas direções) — chamado quando o MP3 é apagado da biblioteca. */
    fun removerPorMp3(ctx: Context, caminhoMp3: String) {
        val caminhoTexto = textoVinculado(ctx, caminhoMp3) ?: return
        prefs(ctx).edit()
            .remove(PREFIXO_MP3_PARA_TEXTO + caminhoMp3)
            .remove(PREFIXO_TEXTO_PARA_MP3 + caminhoTexto)
            .apply()
    }

    /** Migração de caminho (livro foi para a biblioteca): o vínculo passa a sair de [novo], nas duas direções. */
    fun renomearTexto(ctx: Context, antigo: String, novo: String) {
        if (antigo == novo) return
        val mp3 = mp3Vinculado(ctx, antigo) ?: return
        if (mp3Vinculado(ctx, novo) != null) return // nunca sobrescreve um vínculo que o novo caminho já tem
        vincular(ctx, novo, mp3)
    }

    /** Remove só o vínculo do texto (livro removido da biblioteca); o MP3 e seu progresso ficam. */
    fun removerPorTexto(ctx: Context, caminhoTexto: String) {
        val mp3 = mp3Vinculado(ctx, caminhoTexto) ?: return
        prefs(ctx).edit()
            .remove(PREFIXO_TEXTO_PARA_MP3 + caminhoTexto)
            .remove(PREFIXO_MP3_PARA_TEXTO + mp3)
            .apply()
    }

    /** Todos os vínculos texto→mp3 (a direção mp3→texto é reconstruída por [vincular]), para backup. */
    fun exportarTudo(ctx: Context): Map<String, String> =
        prefs(ctx).all
            .filterKeys { it.startsWith(PREFIXO_TEXTO_PARA_MP3) }
            .mapNotNull { (chave, valor) ->
                (valor as? String)?.let { chave.removePrefix(PREFIXO_TEXTO_PARA_MP3) to it }
            }
            .toMap()

    /** Importa de um backup, sem sobrescrever vínculos locais já existentes pro mesmo texto. */
    fun importarMerge(ctx: Context, entradas: Map<String, String>) {
        entradas.forEach { (texto, mp3) -> if (mp3Vinculado(ctx, texto) == null) vincular(ctx, texto, mp3) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
