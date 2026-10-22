package com.jonjonesbr.audiobookgen.util

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/** Ação opcional de uma mensagem: rótulo do botão e o que fazer ao tocar nele. */
typealias AcaoMensagem = Pair<CharSequence, () -> Unit>

/**
 * Mostra um erro como Snackbar (fica até o usuário ler e é anunciado pelo leitor de tela).
 * Se a tela já estiver fechando ou sem view raiz, cai para Toast (a ação é descartada nesse caso).
 */
fun Activity.mostrarErro(@StringRes msgRes: Int, acao: AcaoMensagem? = null) =
    mostrarErro(getString(msgRes), acao)

fun Activity.mostrarErro(msg: CharSequence, acao: AcaoMensagem? = null) =
    mostrarMensagemComAcao(msg, acao)

private fun Activity.mostrarMensagemComAcao(msg: CharSequence, acao: AcaoMensagem?) {
    val raiz = findViewById<View?>(android.R.id.content)
    if (raiz == null || isFinishing || isDestroyed) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        return
    }
    Snackbar.make(raiz, msg, Snackbar.LENGTH_LONG).apply {
        if (acao != null) setAction(acao.first) { acao.second() }
    }.show()
}
