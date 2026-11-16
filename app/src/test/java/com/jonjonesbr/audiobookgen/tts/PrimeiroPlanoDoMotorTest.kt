package com.jonjonesbr.audiobookgen.tts

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

private const val NOTIF_ID_TESTE = 2001
private const val START_ID_TESTE = 1
private const val ESPERA_AVANCO_MS = 65_000L
private const val TEMPERATURA_TESTE = 0.7f

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrimeiroPlanoDoMotorTest {

    @Test
    fun `aoTimeout remove primeiro plano sem lancar e sem nova saida agendada`() {
        val servico = spyk(Robolectric.buildService(SherpaSynthService::class.java).create().get())
        val motor = PrimeiroPlanoDoMotor(servico, NOTIF_ID_TESTE)

        motor.comecar(paraReproducao = false)
        verify(exactly = 1) {
            servico.startForeground(NOTIF_ID_TESTE, any(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        motor.aoTimeout()
        verify(exactly = 1) { servico.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

        // A síntese que já estava em voo termina depois do onTimeout: não deve chamar stopForeground de novo.
        motor.terminar()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ESPERA_AVANCO_MS))
        verify(exactly = 1) { servico.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    @Test
    fun `pos onTimeout com startForeground negado segue sem primeiro plano e sem crash`() {
        val servico = spyk(Robolectric.buildService(SherpaSynthService::class.java).create().get())
        val motor = PrimeiroPlanoDoMotor(servico, NOTIF_ID_TESTE)

        motor.comecar(paraReproducao = false)
        motor.aoTimeout()
        verify(exactly = 1) { servico.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

        every {
            servico.startForeground(any(), any(), any())
        } throws SecurityException("primeiro plano negado em segundo plano")

        // Nova síntese após onTimeout sem permissão de foreground não propaga exceção.
        motor.comecar(paraReproducao = false)
        motor.terminar()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ESPERA_AVANCO_MS))

        verify(exactly = 1) { servico.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    @Test
    fun `sherpa onTimeout delega ao motor sem derrubar o servico`() {
        val servico = Robolectric.buildService(SherpaSynthService::class.java).create().get()
        servico.onBind(Intent())

        servico.onTimeout(START_ID_TESTE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(servico).isForegroundStopped)
        assertTrue(shadowOf(servico).notificationShouldRemoved)
        servico.onDestroy()
    }

    @Test
    fun `pocket pos onTimeout sintetiza sem excecao e sai do foreground apos espera`() {
        val servico = Robolectric.buildService(PocketSynthService::class.java).create().get()
        val binder = servico.onBind(Intent()) as IPocketSynthService

        servico.onTimeout(START_ID_TESTE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertTrue(shadowOf(servico).isForegroundStopped)
        assertTrue(shadowOf(servico).notificationShouldRemoved)

        val retorno = binder.synthesize(
            "teste",
            "voz_inexistente",
            "/tmp/nao_usado.wav",
            1,
            TEMPERATURA_TESTE,
            1,
            false
        )
        assertTrue(retorno.isNotBlank())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ESPERA_AVANCO_MS))
        assertTrue(shadowOf(servico).isForegroundStopped)
        servico.onDestroy()
    }
}
