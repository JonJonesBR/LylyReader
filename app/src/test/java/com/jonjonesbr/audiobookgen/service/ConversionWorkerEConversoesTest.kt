package com.jonjonesbr.audiobookgen.service

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueRepository
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.data.SincroniaAudioStore
import com.jonjonesbr.audiobookgen.domain.CapituloAudio
import com.jonjonesbr.audiobookgen.domain.ProcessamentoResult
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.ui.ConversoesActivity
import com.jonjonesbr.audiobookgen.ui.LeitorTesteApplication
import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture

/**
 * Integração no Checks do lado Kotlin do fluxo de conversão:
 * entrada → [ConversionWorker] (com [PythonEngineUseCase] mockado) → stores de capítulos/sincronia →
 * encerramento na [FilaDeConversao] → abertura da [ConversoesActivity].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class ConversionWorkerEConversoesTest {

    private lateinit var contexto: Context
    private lateinit var pastaTemp: File

    @Before
    fun preparar() {
        zerarFabricaDeViewModel()
        QueueManager.replaceAll(emptyList())
        contexto = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(contexto)
        pastaTemp = File(contexto.cacheDir, "teste_worker_conversoes").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun limpar() {
        unmockkAll()
        QueueManager.replaceAll(emptyList())
        pastaTemp.deleteRecursively()
        zerarFabricaDeViewModel()
    }

    @Test
    fun `conversionWorker conclui item da fila grava metadados e Conversoes abre sem erro`() {
        val entrada = File(pastaTemp, "livro_teste.txt").apply {
            writeText("Capítulo 1\n\nTexto de teste para o worker.")
        }
        val saida = File(pastaTemp, "livro_teste.mp3")
        val itemId = "item-teste-1"
        val runToken = "token-teste-1"
        val prefs = prepararFilaEMockPython(entrada, saida, itemId, runToken)

        val worker = TestListenableWorkerBuilder<ConversionWorker>(contexto)
            .setInputData(
                workDataOf(
                    ConversionWorker.KEY_ARQUIVO to entrada.absolutePath,
                    ConversionWorker.KEY_VOZ to "pt-BR-ThalitaMultilingualNeural",
                    ConversionWorker.KEY_VELOCIDADE to "+0%",
                    ConversionWorker.KEY_ESTILO to "padrao",
                    ConversionWorker.KEY_SAIDA to saida.absolutePath,
                    ConversionWorker.KEY_MOTOR to "auto",
                    ConversionWorker.KEY_FILA_ITEM_ID to itemId,
                    ConversionWorker.KEY_RUN_TOKEN_ENTRADA to runToken,
                )
            )
            .build()

        val resultado = executarBombeandoMain { worker.doWork() }
        assertTrue(
            "Worker deveria concluir com sucesso, mas retornou $resultado",
            resultado is ListenableWorker.Result.Success,
        )
        val out = (resultado as ListenableWorker.Result.Success).outputData
        assertEquals(saida.absolutePath, out.getString(ConversionWorker.KEY_RESULT_CAMINHO))
        assertEquals("0:01", out.getString(ConversionWorker.KEY_RESULT_DURACAO))
        assertTrue(out.getBoolean(ConversionWorker.KEY_RESULT_FINALIZADO, false))

        val capitulosSalvos = ChapterMarksStore.carregar(contexto, saida.absolutePath)
        assertEquals(1, capitulosSalvos.size)
        assertEquals("Capítulo 1", capitulosSalvos.first().titulo)

        val trechosSalvos = SincroniaAudioStore.carregar(contexto, saida.absolutePath)
        assertEquals(1, trechosSalvos.size)
        assertEquals("Texto de teste para o worker.", trechosSalvos.first().texto)

        val filaRestante = runBlocking { QueueRepository.get(contexto).loadItems(prefs) }
        assertTrue("Item concluído deveria sair da fila ativa", filaRestante.none { it.id == itemId })

        ActivityScenario.launch(ConversoesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(!activity.isFinishing)
            }
        }
    }

    private fun prepararFilaEMockPython(
        entrada: File,
        saida: File,
        itemId: String,
        runToken: String,
    ): android.content.SharedPreferences {
        val repo = QueueRepository.get(contexto)
        val prefs = contexto.getSharedPreferences("audiobookgen_prefs", Context.MODE_PRIVATE)
        val itensIniciais = listOf(
            QueueItem(
                id = itemId,
                nome = "Livro Teste",
                caminhoLocal = entrada.absolutePath,
                status = QueueStatus.PROCESSANDO,
                motor = "auto",
                caminhoSaida = saida.absolutePath,
                runToken = runToken,
            )
        )
        QueueManager.replaceAll(itensIniciais)
        runBlocking { repo.replaceAll(itensIniciais) }

        val trechosJson = """[{"inicio_ms":0,"texto":"Texto de teste para o worker."}]"""
        mockkConstructor(PythonEngineUseCase::class)
        coEvery { anyConstructed<PythonEngineUseCase>().inicializarMotorSeNecessario() } returns true
        coEvery {
            anyConstructed<PythonEngineUseCase>().sincronizarConfiguracoes(any(), any(), any(), any(), any(), any())
        } returns Unit
        coEvery { anyConstructed<PythonEngineUseCase>().sincronizarDicionario(any()) } returns Unit
        coEvery {
            anyConstructed<PythonEngineUseCase>().processarArquivo(
                arquivo = any(),
                voz = any(),
                velocidade = any(),
                estilo = any(),
                saida = any(),
                motor = any(),
                chaveGemini = any(),
                etiquetas = any(),
                onProgress = any(),
            )
        } answers {
            // Grava quadro MP3 mínimo para não disparar compressão WAV e simular saída real em disco.
            saida.writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(600))
            ProcessamentoResult.Success(
                caminho = saida.absolutePath,
                duracao = "0:01",
                tamanhoMb = "0.1",
                capitulos = listOf(CapituloAudio("Capítulo 1", 0L, 1200L)),
                trechosJson = trechosJson,
            )
        }
        return prefs
    }

    private fun <T> executarBombeandoMain(bloco: suspend () -> T): T {
        val futuro = CompletableFuture<T>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                futuro.complete(bloco())
            } catch (t: Throwable) {
                futuro.completeExceptionally(t)
            }
        }
        val prazo = System.currentTimeMillis() + 30_000L
        while (!futuro.isDone && System.currentTimeMillis() < prazo) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        check(futuro.isDone) { "Timeout aguardando conclusão na thread principal do Robolectric" }
        return futuro.get()
    }

    private fun zerarFabricaDeViewModel() {
        val tipo = ViewModelProvider.AndroidViewModelFactory::class.java
        tipo.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == tipo }
            .forEach { campo ->
                campo.isAccessible = true
                campo.set(null, null)
            }
    }
}
