package com.jonjonesbr.audiobookgen.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.WeakHashMap

/**
 * Um [DialogosAbertos] por Activity, criado sob demanda. A chave é fraca: quando a Activity some, a entrada some.
 * O valor não guarda a Activity (só o observer no lifecycle dela), então não segura a Activity na memória.
 */
private val dialogosPorActivity = WeakHashMap<AppCompatActivity, DialogosAbertos>()

private fun AppCompatActivity.dialogosAbertos(): DialogosAbertos = synchronized(dialogosPorActivity) {
    dialogosPorActivity.getOrPut(this) { DialogosAbertos(this) }
}

/**
 * Cria e mostra o diálogo, registrando-o para ser fechado quando a Activity for destruída (evita WindowLeaked).
 * Equivale a `builder.show()`: devolve o mesmo [AlertDialog] para quem precisa dos botões depois.
 */
internal fun AppCompatActivity.mostrarDialogo(builder: AlertDialog.Builder): AlertDialog =
    dialogosAbertos().mostrar(builder)

/** Como a versão com [AlertDialog.Builder], para quem já chamou `create()` (ex.: para ligar listeners antes de mostrar). */
internal fun AppCompatActivity.mostrarDialogo(dialogo: AlertDialog): AlertDialog =
    dialogosAbertos().mostrar(dialogo)
