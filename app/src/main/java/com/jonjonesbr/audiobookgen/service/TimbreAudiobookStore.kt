package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.player.TimbreVoz

/**
 * Tom e agudos escolhidos na hora de gerar cada audiobook. O aparelho não tem FFmpeg para gravar o timbre no
 * arquivo, então ele é guardado aqui e aplicado pelo player ao tocar ([AudioPlayerService]).
 *
 * A chave é o `caminho` do audiobook, a mesma de [ChapterMarksStore]. Sem entrada, toca sem ajuste.
 */
object TimbreAudiobookStore {
    private const val PREFS = "timbre_audiobooks"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun salvar(ctx: Context, caminho: String, timbre: TimbreVoz) {
        if (caminho.isBlank()) return
        val editor = prefs(ctx).edit()
        if (timbre.ativo) {
            editor.putString(caminho, "${timbre.meiosTons},${timbre.agudosDb}")
        } else {
            editor.remove(caminho)
        }
        editor.apply()
    }

    /** Timbre de uma conversão: o do perfil do livro, quando veio dele, ou o de Ajustes. */
    fun salvarDaConversao(ctx: Context, caminho: String, tomDoLivro: Int?, agudosDoLivro: Int?) {
        val prefs = AppPrefs(ctx)
        salvar(ctx, caminho, TimbreVoz(tomDoLivro ?: prefs.tomVozMeiosTons, agudosDoLivro ?: prefs.suavizarAgudosDb))
    }

    fun carregar(ctx: Context, caminho: String): TimbreVoz {
        val partes = prefs(ctx).getString(caminho, null)?.split(",") ?: return TimbreVoz.PADRAO
        val tom = partes.getOrNull(0)?.toIntOrNull() ?: 0
        val agudos = partes.getOrNull(1)?.toIntOrNull() ?: 0
        return TimbreVoz(tom, agudos)
    }

    /** Chamado quando o audiobook é apagado da biblioteca. */
    fun remover(ctx: Context, caminho: String) {
        prefs(ctx).edit().remove(caminho).apply()
    }
}
