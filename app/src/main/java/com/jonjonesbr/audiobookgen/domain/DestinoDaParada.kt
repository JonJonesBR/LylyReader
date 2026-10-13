package com.jonjonesbr.audiobookgen.domain

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
}
