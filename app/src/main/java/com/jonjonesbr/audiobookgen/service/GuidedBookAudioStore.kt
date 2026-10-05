package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.PerfilAudioLivro

/** Ajustes de áudio da leitura guiada que valem só para um livro (chave = caminho do livro). */
object GuidedBookAudioStore {
    private const val PREFS = "guided_book_audio"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context, caminho: String): PerfilAudioLivro? {
        if (caminho.isBlank()) return null
        return PerfilAudioLivro.fromJson(prefs(ctx).getString(caminho, null))
    }

    /** `null` apaga o perfil e o livro volta a usar os ajustes globais. */
    fun save(ctx: Context, caminho: String, perfil: PerfilAudioLivro?) {
        if (caminho.isBlank()) return
        val editor = prefs(ctx).edit()
        if (perfil == null) editor.remove(caminho) else editor.putString(caminho, perfil.toJson())
        editor.apply()
    }

    fun remover(ctx: Context, caminho: String) = save(ctx, caminho, null)

    /** Migração de caminho (livro foi para a biblioteca): copia o perfil para [novo] sem sobrescrever o que já existe. */
    fun renomear(ctx: Context, antigo: String, novo: String) {
        val valor = prefs(ctx).getString(antigo, null) ?: return
        if (antigo != novo && !prefs(ctx).contains(novo)) prefs(ctx).edit().putString(novo, valor).apply()
    }
}
