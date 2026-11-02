package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * A tela da Fila foi juntada à de Conversões (C1): a lista agora fica abaixo do que está sendo gerado.
 * Esta Activity só existe para intents antigas e para o atalho de depuração (`.DebugQueue`): abre a
 * [ConversoesActivity] e se fecha. Não tem layout e não usa AppCompat, por causa do tema
 * `Theme.NoDisplay` (declarado no manifesto), que exige encerrar antes do `onResume`.
 */
class QueueActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ConversoesActivity::class.java))
        finish()
    }
}
