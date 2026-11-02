package com.jonjonesbr.audiobookgen.player

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.ui.LeitorTesteApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * rb-2: ciclo de vida do [ExoPlayerEngineImpl] sem reprodução de áudio. O erro de arquivo inexistente sai do loader,
 * então não depende de renderer. O loader não desiste na hora: o FileDataSourceException não é FileNotFoundException,
 * então a política de erro do media3 tenta de novo algumas vezes, com atraso (relógio falso). Por isso as esperas pelo
 * erro usam 15 s de relógio falso, que custam pouco tempo real. A thread de reprodução do ExoPlayer é real:
 * [esperarAte] avança a main looper e cede a thread, sem espera fixa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class ExoPlayerEngineImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var motor: ExoPlayerEngineImpl
    private val erros = CopyOnWriteArrayList<String>()
    private var fins = 0

    @Before
    fun preparar() {
        motor = ExoPlayerEngineImpl(ApplicationProvider.getApplicationContext())
        motor.setCompletionListener { fins++ }
        motor.setErrorListener { erros.add(it) }
    }

    @After
    fun encerrar() {
        // Obrigatório: sem o release a thread de reprodução do ExoPlayer fica viva depois do teste.
        motor.release()
    }

    private fun esperarAte(limiteMs: Long = 5_000, cond: () -> Boolean): Boolean {
        repeat((limiteMs / 20).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (cond()) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Chamadas de controle sem player: não podem lançar, e o estado fica zerado. */
    private fun chamadasSemPlayer() {
        motor.pause()
        motor.resume()
        motor.stop()
        motor.seekTo(10)
        motor.setPlaybackSpeed(2f)
        motor.setVolume(0.5f)
        motor.setTimbre(TimbreVoz(2, 6))
        assertEquals(0, motor.getCurrentPosition())
        assertEquals(0, motor.getDuration())
        assertFalse(motor.isPlaying())
    }

    @Test
    fun chamadasSemPlayNaoLancamENemRelease() {
        chamadasSemPlayer()
        motor.release()
        motor.release()
    }

    @Test
    fun arquivoInexistenteChamaOListenerDeErro() {
        motor.play(File(tmp.root, "nao_existe.wav"), 1f)
        assertTrue(esperarAte(15_000) { erros.isNotEmpty() })
        assertTrue(erros.first().startsWith("ExoPlayer error"))
        assertEquals(0, fins)
        assertFalse(motor.isPlaying())
    }

    @Test
    fun depoisDoErroReleaseDuploEChamadasNaoLancam() {
        motor.play(File(tmp.root, "nao_existe.wav"), 1f)
        assertTrue(esperarAte(15_000) { erros.isNotEmpty() })
        motor.release()
        motor.release()
        chamadasSemPlayer()
    }
}
