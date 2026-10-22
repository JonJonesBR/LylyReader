package com.jonjonesbr.audiobookgen.domain

import android.os.Build
import androidx.work.WorkInfo

/** Quem pediu a parada da conversão, quando foi o app (pausa ou cancelamento). */
enum class MotivoParada { PAUSAR, CANCELAR, LIMITE_DO_SISTEMA }

/** Para onde vai o item da fila quando a execução para. */
enum class DestinoDaParada { PAUSADA, CANCELADA, INTERROMPIDA }

/**
 * Regra pura: decide o destino de uma parada. Sem Android.
 * Sem pedido do app, só é cancelamento se o próprio WorkManager cancelou (canceladoPeloApp); senão a parada foi do sistema
 * e o trabalho será reexecutado.
 */
object RegraDaParada {

    fun destino(pedido: MotivoParada?, canceladoPeloApp: Boolean): DestinoDaParada = when (pedido) {
        MotivoParada.PAUSAR, MotivoParada.LIMITE_DO_SISTEMA -> DestinoDaParada.PAUSADA
        MotivoParada.CANCELAR -> DestinoDaParada.CANCELADA
        null -> if (canceladoPeloApp) DestinoDaParada.CANCELADA else DestinoDaParada.INTERROMPIDA
    }

    /**
     * Se o WorkManager cancelou o trabalho pelo app. A partir do Android 12 o motivo vem de [stopReason]; NOT_STOPPED conta como
     * cancelamento (o motivo ainda não foi gravado). Antes disso o motivo não existe e segue como cancelamento pelo app.
     */
    fun canceladoPeloApp(sdk: Int, stopReason: Int): Boolean =
        if (sdk >= Build.VERSION_CODES.S) {
            stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP || stopReason == WorkInfo.STOP_REASON_NOT_STOPPED
        } else {
            true
        }
}
