package com.jonjonesbr.audiobookgen.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jonjonesbr.audiobookgen.domain.MotivoParada

/**
 * Recebe o toque no botão "Pausar" da notificação de andamento da conversão. Não é exportado:
 * a PendingIntent aponta para este componente com intent explícita.
 */
class AcaoDaConversaoReceiver : BroadcastReceiver() {

    companion object {
        const val ACAO_PAUSAR = "com.jonjonesbr.audiobookgen.action.CONVERSAO_PAUSAR"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACAO_PAUSAR) {
            ControleDaConversao.pedirPausa(context, MotivoParada.PAUSAR)
        }
    }
}
