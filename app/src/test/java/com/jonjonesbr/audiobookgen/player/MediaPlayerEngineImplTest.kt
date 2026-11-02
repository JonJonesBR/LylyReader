package com.jonjonesbr.audiobookgen.player

import android.media.MediaPlayer
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.time.Duration
import kotlin.math.pow

/**
 * rb-2: ciclo de vida do [MediaPlayerEngineImpl] sobre o ShadowMediaPlayer. O tempo corre pela main looper (idleFor),
 * então nenhum teste espera tempo real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = LeitorTesteApplication::class,
    shadows = [ShadowMediaPlayerComParametros::class]
)
class MediaPlayerEngineImplTest {

    private companion object {
        const val DURACAO_MS = 1000
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var arquivo: File
    private lateinit var motor: MediaPlayerEngineImpl
    private val criados = mutableListOf<ShadowMediaPlayerComParametros>()
    private val erros = mutableListOf<String>()
    private var fins = 0

    @Before
    fun preparar() {
        arquivo = tmp.newFile("trecho.wav")
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(arquivo.absolutePath),
            ShadowMediaPlayer.MediaInfo(DURACAO_MS, 0)
        )
        ShadowMediaPlayer.setCreateListener { _, shadow ->
            criados.add(shadow as ShadowMediaPlayerComParametros)
        }
        motor = MediaPlayerEngineImpl(ApplicationProvider.getApplicationContext())
        motor.setCompletionListener { fins++ }
        motor.setErrorListener { erros.add(it) }
    }

    @After
    fun encerrar() {
        motor.release()
    }

    private fun andar(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** Último MediaPlayer criado pelo motor (o que está em uso). */
    private fun ultimo(): ShadowMediaPlayerComParametros = criados.last()

    @Test
    fun playComecaATocarEInformaADuracao() {
        motor.play(arquivo, 1f)
        assertTrue(motor.isPlaying())
        assertEquals(DURACAO_MS, motor.getDuration())
        assertTrue(erros.isEmpty())
    }

    @Test
    fun pausaERetomaEPausarDeNovoNaoLanca() {
        motor.play(arquivo, 1f)
        motor.pause()
        assertFalse(motor.isPlaying())
        motor.pause()
        assertFalse(motor.isPlaying())
        motor.resume()
        assertTrue(motor.isPlaying())
    }

    @Test
    fun pausarEResumirSemPlayNaoLancam() {
        motor.pause()
        motor.resume()
        assertFalse(motor.isPlaying())
    }

    @Test
    fun timbreAjustadoAntesDoPlayEntraNoTomDoPlayer() {
        motor.setTimbre(TimbreVoz(-2, 0))
        motor.play(arquivo, 1.25f)
        val params = ultimo().parametros.last()
        assertEquals(1.25f, params.speed, 0f)
        assertEquals(2.0.pow(-1 / 12.0).toFloat(), params.pitch, 1e-5f)
    }

    @Test
    fun setPlaybackSpeedComTocandoAplicaNaHora() {
        motor.play(arquivo, 1f)
        motor.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, ultimo().parametros.last().speed, 0f)
    }

    @Test
    fun setPlaybackSpeedPausadoNaoMexeNoPlayerEValeNoResume() {
        motor.play(arquivo, 1f)
        motor.pause()
        val n = ultimo().parametros.size
        motor.setPlaybackSpeed(2f)
        assertEquals(n, ultimo().parametros.size)
        assertFalse(motor.isPlaying())
        motor.resume()
        assertEquals(2f, ultimo().parametros.last().speed, 0f)
        assertTrue(motor.isPlaying())
    }

    @Test
    fun setTimbreComTocandoMantemAVelocidadeEPausadoNaoAplica() {
        motor.play(arquivo, 1f)
        motor.setTimbre(TimbreVoz(2, 6))
        val params = ultimo().parametros.last()
        assertEquals(1f, params.speed, 0f)
        assertEquals(2.0.pow(1 / 12.0).toFloat(), params.pitch, 1e-5f)
        assertTrue(motor.isPlaying())

        motor.pause()
        val n = ultimo().parametros.size
        motor.setTimbre(TimbreVoz(-2, 0))
        assertEquals(n, ultimo().parametros.size)
    }

    @Test
    fun fimDaFaixaChamaOListenerDeFim() {
        motor.play(arquivo, 1f)
        andar(DURACAO_MS + 100L)
        assertEquals(1, fins)
        assertFalse(motor.isPlaying())
    }

    @Test
    fun erroAgendadoChamaOListenerDeErroSemLancar() {
        val comErro = tmp.newFile("com_erro.wav")
        val info = ShadowMediaPlayer.MediaInfo(DURACAO_MS, 0)
        info.scheduleErrorAtOffset(200, MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(comErro.absolutePath), info)

        motor.play(comErro, 1f)
        andar(300)
        assertEquals(listOf("MediaPlayer error: what=1, extra=-1004"), erros)
        assertEquals(0, fins)
        motor.stop()
    }

    @Test
    fun arquivoSemMediaInfoDaErroSemLancar() {
        motor.play(File(tmp.root, "nao_existe.wav"), 1f)
        assertEquals(1, erros.size)
        assertTrue(erros.single().startsWith("Error starting playback"))
        assertFalse(motor.isPlaying())
        // Comportamento atual, não desejado: o MediaPlayer que falhou não é liberado (dúvida 1 do desenho rb-2).
        assertTrue(ultimo().state != ShadowMediaPlayer.State.END)
    }

    @Test
    fun volumePedidoAntesDoPlayValeNoPlayerNovo() {
        motor.setVolume(0.3f)
        motor.play(arquivo, 1f)
        assertEquals(0.3f, ultimo().leftVolume, 1e-6f)
    }

    @Test
    fun segundoPlayEncerraOAnterior() {
        motor.play(arquivo, 1f)
        motor.play(arquivo, 1f)
        assertEquals(2, criados.size)
        assertEquals(ShadowMediaPlayer.State.END, criados[0].state)
        assertTrue(motor.isPlaying())
    }

    @Test
    fun depoisDeStopEReleaseChamadasNaoLancam() {
        motor.play(arquivo, 1f)
        motor.stop()
        motor.stop()
        motor.release()
        motor.release()
        motor.pause()
        motor.resume()
        motor.seekTo(10)
        motor.setPlaybackSpeed(1.5f)
        motor.setVolume(0.5f)
        motor.setTimbre(TimbreVoz(2, 6))
        assertEquals(0, motor.getCurrentPosition())
        assertEquals(0, motor.getDuration())
        assertFalse(motor.isPlaying())
        assertEquals(ShadowMediaPlayer.State.END, ultimo().state)
    }
}
