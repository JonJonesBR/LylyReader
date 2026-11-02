package com.jonjonesbr.audiobookgen.util

import android.content.Context
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.ModoSoneca
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.service.modoDaSoneca

/** Texto curto da soneca (chip, notificação) e a descrição para o TalkBack, conforme o modo. */
internal data class TextosSoneca(val rotulo: String, val descricao: String)

/**
 * Textos da soneca em vigor: contagem ("😴 mm:ss"), fim do capítulo ("😴 fim do cap.") ou desligada ("😴").
 * [restanteMs] null = sem contagem; [fimDoCapitulo] = soneca "fim do capítulo" armada.
 */
internal fun Context.textosDaSoneca(restanteMs: Long?, fimDoCapitulo: Boolean): TextosSoneca =
    when (modoDaSoneca(restanteMs, fimDoCapitulo)) {
        ModoSoneca.DESLIGADA -> TextosSoneca("😴", getString(R.string.cd_sleep_timer))
        ModoSoneca.CONTAGEM -> restanteMs?.let { ms ->
            val tempo = SleepTimerManager.formatRemaining(ms)
            TextosSoneca("😴 $tempo", getString(R.string.sleep_timer_title_active, tempo))
        } ?: TextosSoneca("😴", getString(R.string.cd_sleep_timer))
        ModoSoneca.FIM_DO_CAPITULO -> TextosSoneca(
            "😴 " + getString(R.string.sleep_timer_fim_capitulo_curto),
            getString(R.string.sleep_timer_title_fim_capitulo)
        )
    }

/**
 * Rótulo do botão Soneca do mini-player (o ícone fica ao lado, no XML). Desarmada, a palavra "Soneca";
 * armada, só a contagem ou o "fim do cap.", porque três botões na linha cortam o texto longo e a contagem
 * some. A palavra segue na descrição para o TalkBack ([textosDaSoneca]). Sem o emoji, que fica na notificação.
 */
internal fun Context.rotuloSonecaMiniPlayer(restanteMs: Long?, fimDoCapitulo: Boolean): String {
    val palavra = getString(R.string.mini_player_soneca)
    return when (modoDaSoneca(restanteMs, fimDoCapitulo)) {
        ModoSoneca.DESLIGADA -> palavra
        ModoSoneca.CONTAGEM -> restanteMs?.let { SleepTimerManager.formatRemaining(it) } ?: palavra
        ModoSoneca.FIM_DO_CAPITULO -> getString(R.string.sleep_timer_fim_capitulo_curto)
    }
}
