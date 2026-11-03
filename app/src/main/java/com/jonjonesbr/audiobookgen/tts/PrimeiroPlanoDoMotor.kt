package com.jonjonesbr.audiobookgen.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jonjonesbr.audiobookgen.R

/**
 * Primeiro plano dos motores de voz (rb-36), compartilhado por [SupertonicSynthService],
 * [SherpaSynthService] e [PocketSynthService]. Cada processo tem a sua instância, com a própria
 * notificação ([notifId]) e o canal comum [CHANNEL_ID].
 *
 * Entra no primeiro plano na primeira síntese e sai [ESPERA_PARA_SAIR_MS] depois da última.
 * Antes, cada síntese fazia startForeground/stopForeground: a notificação era recriada e mudava
 * de lugar na lista. Com a espera, ela fica parada enquanto a leitura ou a conversão continua.
 *
 * Custo no limite do dataSync (Android 15+): como as orações da leitura guiada saem a menos de
 * [ESPERA_PARA_SAIR_MS] uma da outra, o primeiro plano do motor dura a sessão inteira, e não só
 * as sínteses. Esse tempo conta para o limite de 6 h por 24 h do dataSync (o GuidedReadingService
 * é mediaPlayback e não entra na conta). Ao estourar, vem onTimeout, e aoTimeout trata a saída.
 * Se isso aparecer em uso real, baixar ESPERA_PARA_SAIR_MS para uns 20 s.
 *
 * Todo acesso ao estado passa pela [trava]. A regra fica em [CicloPrimeiroPlanoMotor].
 */
internal class PrimeiroPlanoDoMotor(private val service: Service, private val notifId: Int) {

    private val handler = Handler(Looper.getMainLooper())
    private val trava = Any()
    private val ciclo = CicloPrimeiroPlanoMotor()

    // Fixo pela vida do processo: a notificação não muda de horário a cada síntese.
    private val criadoEm = System.currentTimeMillis()
    private var saidaPendente: Runnable? = null

    /** Início de uma síntese. Nunca lança: se o primeiro plano for negado, sintetiza mesmo assim. */
    fun comecar() {
        synchronized(trava) {
            cancelarSaida()
            if (ciclo.aoComecar()) ciclo.confirmarPrimeiroPlano(entrar())
        }
    }

    /** Fim de uma síntese. Agenda a saída para daqui a [ESPERA_PARA_SAIR_MS], se for a última. */
    fun terminar() {
        synchronized(trava) {
            // Uma espera anterior ainda agendada sai antes da nova, para não ficar órfã.
            cancelarSaida()
            val ficha = ciclo.aoTerminar() ?: return
            val saida = Runnable {
                synchronized(trava) {
                    if (ciclo.aoVencerEspera(ficha)) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                }
            }
            saidaPendente = saida
            handler.postDelayed(saida, ESPERA_PARA_SAIR_MS)
        }
    }

    /** Chamado por Service.onTimeout (Android 15+): o sistema encerrou o primeiro plano. */
    fun aoTimeout() {
        synchronized(trava) {
            cancelarSaida()
            ciclo.aoSairForcado()
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
    }

    /** Chamado por Service.onDestroy: só limpa a saída agendada. */
    fun encerrar() {
        synchronized(trava) {
            cancelarSaida()
            ciclo.aoSairForcado()
        }
    }

    private fun cancelarSaida() {
        saidaPendente?.let { handler.removeCallbacks(it) }
        saidaPendente = null
    }

    private fun entrar(): Boolean = runCatching {
        criarCanal()
        val notif = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lyly)
            .setContentTitle(service.getString(R.string.notif_preparando_voz_titulo))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setWhen(criadoEm)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            service.startForeground(notifId, notif)
        }
    }.onFailure {
        // Sem stack trace: com a nova tentativa a cada síntese, a falha pode se repetir no lote.
        Log.w(TAG, "Primeiro plano do motor de voz negado (${it.javaClass.simpleName}); segue sem ele")
    }.isSuccess

    private fun criarCanal() {
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notif_canal_motor_voz),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
        // Canais dos motores antigos: somem dos Ajustes de notificação. Apagar um canal que não
        // existe não faz nada; o runCatching cobre o caso de o sistema recusar a remoção.
        CANAIS_ANTIGOS.forEach { canal -> runCatching { nm.deleteNotificationChannel(canal) } }
    }

    companion object {
        const val CHANNEL_ID = "lylyreader_motor_voz"
        const val ESPERA_PARA_SAIR_MS = 60_000L
        private const val TAG = "PrimeiroPlanoMotor"
        private val CANAIS_ANTIGOS = listOf(
            "lylyreader_supertonic_synth",
            "lylyreader_sherpa_synth",
            "lylyreader_pocket_synth"
        )
    }
}
