package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueRepository
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.data.SincroniaAudioStore
import com.jonjonesbr.audiobookgen.service.ChapterMarksStore
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import com.jonjonesbr.audiobookgen.service.ParametrosConversao
import com.jonjonesbr.audiobookgen.service.QueueManager
import com.jonjonesbr.audiobookgen.tts.OnnxSynthBridge
import com.jonjonesbr.audiobookgen.tts.SilentWav
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

private const val TIMEOUT_CONVERSAO_MS = 60_000L
private const val INTERVALO_POLL_MS = 200L
private const val DURACAO_BLOCO_WAV_MS = 600

/**
 * Fluxo real curto de conversão de texto em audiobook no emulador (UI Tests):
 * entrada TXT → [ConversionWorker] → Chaquopy/Python (extração, chunks, cache, junção) →
 * [OnnxSynthBridge] com voz offline Supertonic (`supertonic-m1`, áudio sintético sem rede) →
 * stores de capítulos/sincronia → cartão pronto em [ConversoesActivity].
 */
@RunWith(AndroidJUnit4::class)
class ConversaoAudiobookNoAparelhoTest {

    @Test
    fun conversaoCurtaComSupertonicConcluiEMostraProntoEmConversoes() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val contexto = instrumentacao.targetContext
        AppPrefs(contexto).onboardingDone = true

        val pastaTeste = File(contexto.cacheDir, "teste_conversao_ui").apply {
            deleteRecursively()
            mkdirs()
        }
        val entrada = File(pastaTeste, "livro_ui_teste.txt").apply {
            val cap1 = "Capítulo 1 — Início\n\n" +
                "Este é um teste real curto de conversão em audiobook no emulador. ".repeat(8)
            val cap2 = "\n\nCapítulo 2 — Fim\n\n" +
                "Depois dos blocos locais, a junção e os capítulos são gravados no aparelho. ".repeat(8)
            writeText(cap1 + cap2)
        }
        val saida = File(contexto.filesDir, "livro_ui_teste_${System.currentTimeMillis()}.mp3")
        val itemId = "ui-conv-${UUID.randomUUID()}"
        val runToken = UUID.randomUUID().toString()

        OnnxSynthBridge.sinteseParaTestes = { _, _, destino ->
            destino.writeBytes(SilentWav.bytes(DURACAO_BLOCO_WAV_MS))
            true
        }

        val scenario = ActivityScenario.launch<ConversoesActivity>(
            Intent(contexto, ConversoesActivity::class.java)
        )

        try {
            val repo = QueueRepository.get(contexto)
            val prefs = contexto.getSharedPreferences("audiobookgen_prefs", android.content.Context.MODE_PRIVATE)
            val itensIniciais = listOf(
                QueueItem(
                    id = itemId,
                    nome = "Livro UI Teste",
                    caminhoLocal = entrada.absolutePath,
                    status = QueueStatus.PROCESSANDO,
                    motor = "onnx",
                    caminhoSaida = saida.absolutePath,
                    runToken = runToken,
                )
            )
            QueueManager.replaceAll(itensIniciais)
            runBlocking { repo.replaceAll(itensIniciais) }

            val parametros = ParametrosConversao(
                arquivoEntrada = entrada.absolutePath,
                voz = "supertonic-m1",
                velocidade = "+0%",
                estilo = "padrao",
                caminhoSaidaInterna = saida.absolutePath,
                motor = "onnx",
                runToken = runToken,
                livroOrigemCaminho = entrada.absolutePath,
                filaItemId = itemId,
                livroDicionarioCaminho = entrada.absolutePath,
            )
            val pedido = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(parametros.toInputData(AppPrefs(contexto).pausaMs))
                .build()
            WorkManager.getInstance(contexto)
                .enqueueUniqueWork(ConversionWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, pedido)

            val prazo = SystemClock.uptimeMillis() + TIMEOUT_CONVERSAO_MS
            var pronto = false
            while (SystemClock.uptimeMillis() < prazo) {
                var erroVisivel: String? = null
                scenario.onActivity { activity ->
                    val cardPronto = activity.findViewById<View>(R.id.cardConvPronto)
                    val cardErro = activity.findViewById<View>(R.id.cardConvErro)
                    if (cardPronto.visibility == View.VISIBLE) {
                        pronto = true
                    } else if (cardErro.visibility == View.VISIBLE) {
                        erroVisivel = activity.findViewById<TextView>(R.id.tvConvErroMsg).text?.toString()
                    }
                }
                if (pronto) break
                if (erroVisivel != null) {
                    fail("Conversão terminou em erro na tela Conversões: $erroVisivel")
                }
                SystemClock.sleep(INTERVALO_POLL_MS)
            }
            assertTrue("Cartão de conversão pronta não apareceu em ${TIMEOUT_CONVERSAO_MS}ms", pronto)

            Capturas.capturar("conversao_audiobook_pronta")

            scenario.onActivity { activity ->
                val info = activity.findViewById<TextView>(R.id.tvConvProntoInfo).text?.toString().orEmpty()
                assertTrue("Texto de conclusão deveria estar preenchido", info.isNotBlank())
            }

            assertTrue("Arquivo de saída deveria existir após a conversão", saida.isFile && saida.length() > 0)
            assertTrue(
                "Capítulos deveriam ter sido salvos em ChapterMarksStore",
                ChapterMarksStore.carregar(contexto, saida.absolutePath).isNotEmpty(),
            )
            assertTrue(
                "Trechos de sincronia deveriam ter sido salvos em SincroniaAudioStore",
                SincroniaAudioStore.carregar(contexto, saida.absolutePath).isNotEmpty(),
            )
            val filaRestante = runBlocking { repo.loadItems(prefs) }
            assertTrue("Item concluído deveria ter saído da fila", filaRestante.none { it.id == itemId })
        } finally {
            OnnxSynthBridge.sinteseParaTestes = null
            QueueManager.replaceAll(emptyList())
            WorkManager.getInstance(contexto).cancelUniqueWork(ConversionWorker.WORK_NAME)
            scenario.close()
            pastaTeste.deleteRecursively()
            saida.delete()
            ChapterMarksStore.remover(contexto, saida.absolutePath)
            SincroniaAudioStore.remover(contexto, saida.absolutePath)
        }
    }
}
