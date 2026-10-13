package com.jonjonesbr.audiobookgen.service

import android.content.Context
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.domain.MotivoParada
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase

/**
 * Pedido de parada da conversão em andamento. O worker lê [pedido] quando para, para saber se foi o app/usuário
 * (pausar ou cancelar) ou o sistema (cota, limite de runtime).
 */
object ControleDaConversao {

    @Volatile
    var pedido: MotivoParada? = null

    /**
     * Cancela pelo usuário: grava o pedido, cancela o trabalho único e sinaliza o Python para parar e apagar o job
     * (o próprio Python apaga, depois que a síntese termina, sem corrida).
     */
    fun pedirCancelamento(context: Context) {
        pedido = MotivoParada.CANCELAR
        WorkManager.getInstance(context).cancelUniqueWork(ConversionWorker.WORK_NAME)
        PythonEngineUseCase(context.applicationContext).cancelar(descartar = true)
    }

    /** Só grava o pedido e sinaliza o Python; não cancela o trabalho único (quem trata o resultado é a pausa, T5). */
    fun pedirPausa(context: Context, motivo: MotivoParada) {
        pedido = motivo
        PythonEngineUseCase(context.applicationContext).cancelar()
    }

    fun limpar() {
        pedido = null
    }
}
