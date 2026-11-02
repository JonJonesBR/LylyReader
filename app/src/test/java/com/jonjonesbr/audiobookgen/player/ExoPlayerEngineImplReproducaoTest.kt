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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * rb-2, tentativa: reprodução de um WAV silencioso gerado no próprio teste. Depende de o PCM passar pelo renderer de
 * áudio e do ShadowAudioTrack no SDK 34, por isso fica em classe separada: se não rodar na JVM, a classe inteira recebe
 * @Ignore com o motivo, sem mexer no motor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class ExoPlayerEngineImplReproducaoTest {

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

    /** WAV PCM 16 bits, mono, 16 kHz, com [ms] milissegundos de silêncio. */
    private fun wavSilencioso(arquivo: File, ms: Int = 300) {
        val n = 16000 * 2 * ms / 1000
        val bytes = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + n)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bytes.putShort(1) // PCM
        bytes.putShort(1) // mono
        bytes.putInt(16000) // taxa de amostragem
        bytes.putInt(32000) // bytes por segundo (16000 * 2)
        bytes.putShort(2) // bytes por quadro
        bytes.putShort(16) // bits por amostra
        bytes.put("data".toByteArray(Charsets.US_ASCII)).putInt(n)
        // Os n bytes seguintes ficam zerados: é o silêncio.
        arquivo.writeBytes(bytes.array())
    }

    /**
     * Não tenta ver `isPlaying() == true` numa janela curta: no ShadowAudioTrack o que já foi escrito conta como tocado,
     * e a faixa pode acabar antes da checagem. Pausa logo após o play, sem avançar a main looper: pausado não termina,
     * então nenhum fim pode chegar nos 500 ms seguintes; depois da retomada a faixa termina e o erro não chega.
     */
    @Test
    fun pausaLogoAposPlaySeguraORetomaTermina() {
        val arquivo = tmp.newFile("silencio.wav")
        wavSilencioso(arquivo)

        motor.play(arquivo, 1f)
        motor.pause()
        assertFalse(motor.isPlaying())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, fins)
        motor.resume()
        assertTrue(esperarAte(10_000) { fins == 1 })
        assertTrue(erros.isEmpty())
    }
}
