package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Paragrafo

/**
 * Parágrafo que o "compartilhar trecho" envia: o da narração (guiada ou audiobook) quando há uma; senão o
 * primeiro visível na tela, avaliado só nesse caso (por isso é uma função, não um valor). Devolve nulo se o
 * índice cair fora da lista ou se o parágrafo estiver em branco.
 */
internal fun paragrafoParaCompartilhar(
    paragrafos: List<Paragrafo>?,
    indiceGuiada: Int?,
    indiceNaTela: () -> Int?,
): Paragrafo? {
    val index = indiceGuiada?.takeIf { it >= 0 } ?: indiceNaTela() ?: -1
    val paragrafo = if (index in 0 until (paragrafos?.size ?: 0)) paragrafos?.get(index) else null
    return paragrafo?.takeIf { it.texto.trim().isNotEmpty() }
}

/** Abre o compartilhamento do parágrafo com a assinatura do livro; sem parágrafo, avisa com um toast. */
internal fun compartilharTrecho(activity: Activity, paragrafo: Paragrafo?) {
    if (paragrafo == null) {
        Toast.makeText(activity, R.string.toast_nada_para_compartilhar, Toast.LENGTH_SHORT).show()
        return
    }
    val textoCompartilhar = paragrafo.texto + activity.getString(R.string.share_excerpt_signature)
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, textoCompartilhar)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, activity.getString(R.string.share_excerpt_chooser))
    activity.startActivity(shareIntent)
}
