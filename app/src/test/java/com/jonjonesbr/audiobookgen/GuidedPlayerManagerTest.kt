package com.jonjonesbr.audiobookgen

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.player.MediaPlayerEngineImpl
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [GuidedPlayerManager] não tem ponto de injeção para AudioPlayerEngine,
 * [AppPrefs] ou o pipeline de síntese TTS — todos são construídos internamente a partir do
 * [Context] recebido no construtor. Por isso os testes usam `mockkConstructor` para interceptar
 * essas construções internas, e reflexão para os poucos campos privados (`currentIndex`,
 * `prefetchJob`, `_state`) sem contrapartida pública, evitando arrastar o pipeline assíncrono
 * completo de `play()` (que depende de `Dispatchers.IO` real) para testes que só precisam
 * exercitar `pause()`/`resume()`/`setParagraphs()`.
 */
class GuidedPlayerManagerTest {

    private lateinit var testDispatcher: TestDispatcher
    private val createdManagers = mutableListOf<GuidedPlayerManager>()

    private lateinit var lastErrorListener: (String) -> Unit
    private lateinit var lastCompletionListener: () -> Unit

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkConstructor(AppPrefs::class)
        every { anyConstructed<AppPrefs>().playerEngine } returns "media_player"
        every { anyConstructed<AppPrefs>().guidedSpeedMult } returns 1.0f

        val errorSlot = slot<(String) -> Unit>()
        val completionSlot = slot<() -> Unit>()
        mockkConstructor(MediaPlayerEngineImpl::class)
        every {
            anyConstructed<MediaPlayerEngineImpl>().setErrorListener(capture(errorSlot))
        } answers { lastErrorListener = errorSlot.captured }
        every {
            anyConstructed<MediaPlayerEngineImpl>().setCompletionListener(capture(completionSlot))
        } answers { lastCompletionListener = completionSlot.captured }
        every { anyConstructed<MediaPlayerEngineImpl>().pause() } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().resume() } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().stop() } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().release() } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns false
        every { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() } returns 0
        every { anyConstructed<MediaPlayerEngineImpl>().getDuration() } returns 0
        every { anyConstructed<MediaPlayerEngineImpl>().setVolume(any()) } just Runs
    }

    @After
    fun tearDown() {
        createdManagers.forEach { runCatching { it.release() } }
        createdManagers.clear()
        GuidedPlaybackBridge.manager?.let { GuidedPlaybackBridge.deactivate(it) }
        GuidedPlaybackBridge.activeState.value = null
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createManager(): GuidedPlayerManager {
        val context = mockk<Context>(relaxed = true)
        val pythonUseCase = mockk<PythonEngineUseCase>(relaxed = true)
        return GuidedPlayerManager(context, pythonUseCase).also { createdManagers.add(it) }
    }

    private fun paragraphs(count: Int) =
        (0 until count).map { Paragrafo(texto = "paragrafo-$it", charOffset = 0, indiceCapitulo = it) }

    @Test
    fun paragrafoUnicoSupertonicPreparaSeguinteEnquantoToca() {
        verificarPrefetchDuranteParagrafoUnico("onnx", "supertonic-f4-pt")
    }

    @Test
    fun paragrafoUnicoKokoroPreparaSeguinteEnquantoToca() {
        verificarPrefetchDuranteParagrafoUnico("kokoro", "kokoro-pf-dora")
    }

    @Test
    fun paragrafoUnicoMmsPelaRotaKokoroPreparaSeguinteEnquantoToca() {
        verificarPrefetchDuranteParagrafoUnico("kokoro", "byom-mms-por")
    }

    @Test
    fun guiadaSintetizaFalaComVozDoPersonagemEContinuacaoComNarrador() {
        val texto = "“Eu volto”, disse Marina."
        val arquivo = java.io.File.createTempFile("guiada-falantes-", ".wav")
        val synthesizer = mockk<com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>()
        io.mockk.coEvery { synthesizer.synthesize(any(), any(), 100) } returns arquivo.absolutePath
        mockkObject(com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.Companion)
        every { com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para("edge", any(), any(), any()) } returns synthesizer
        mockkObject(com.jonjonesbr.audiobookgen.service.GuidedReadingService.Companion)
        every { com.jonjonesbr.audiobookgen.service.GuidedReadingService.ativo } returns true
        every { anyConstructed<AppPrefs>().motorTts } returns "edge"
        every { anyConstructed<AppPrefs>().vozSelecionada } returns "pt-BR-ThalitaMultilingualNeural"
        every { anyConstructed<AppPrefs>().ritmo } returns 100
        every { anyConstructed<MediaPlayerEngineImpl>().play(any(), any()) } just Runs
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) } returns false

        val manager = createManager()
        try {
            manager.setParagraphs(listOf(Paragrafo(texto = texto, charOffset = 0, indiceCapitulo = 0)))
            val attribution = OfflineSpeakerAttributor.analyze(listOf(texto)).copy(
                speakers = OfflineSpeakerAttributor.analyze(listOf(texto)).speakers.map {
                    it.copy(voiceId = "pt-BR-FranciscaNeural")
                }
            )
            manager.setSpeakerAttribution(attribution)
            manager.play(0)

            aguardar { manager.state.value.isPlaying }
            aguardar { coVerificationCount(synthesizer, "“Eu volto”", "pt-BR-FranciscaNeural") == 1 }
            lastCompletionListener()
            aguardar { playerPlayCount() == 2 }
            io.mockk.coVerify(exactly = 1) {
                synthesizer.synthesize(", disse Marina.", "pt-BR-ThalitaMultilingualNeural", 100)
            }
            lastCompletionListener()
            aguardar { manager.state.value.index == -1 }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    private fun aguardar(condicao: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condicao() && System.nanoTime() < deadline) {
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(1)
        }
        assertTrue("Condição de playback não atingida", condicao())
    }

    private fun coVerificationCount(
        synthesizer: com.jonjonesbr.audiobookgen.tts.TtsSynthesizer,
        text: String,
        voice: String
    ): Int = runCatching {
        io.mockk.coVerify(exactly = 1) { synthesizer.synthesize(text, voice, 100) }
        1
    }.getOrDefault(0)

    private fun playerPlayCount(): Int = runCatching {
        io.mockk.verify(exactly = 2) { anyConstructed<MediaPlayerEngineImpl>().play(any(), any()) }
        2
    }.getOrDefault(0)

    private fun verificarPrefetchDuranteParagrafoUnico(motor: String, voz: String) {
        val atual = "Uma frase curta."
        val seguinte = "O próximo parágrafo deve começar a ser preparado enquanto a frase anterior ainda toca."
        val arquivo = java.io.File.createTempFile("guiada-teste-", ".wav")
        val prefetchIniciado = java.util.concurrent.CountDownLatch(1)
        val terminarPrefetch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sintetizador = mockk<com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>()
        io.mockk.coEvery { sintetizador.synthesize(atual, voz, 100) } returns arquivo.absolutePath
        io.mockk.coEvery { sintetizador.synthesize(seguinte, voz, 100) } coAnswers {
            prefetchIniciado.countDown()
            terminarPrefetch.await()
            arquivo.absolutePath
        }
        mockkObject(com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.Companion)
        every { com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(motor, any(), any(), any()) } returns sintetizador
        mockkObject(com.jonjonesbr.audiobookgen.service.GuidedReadingService.Companion)
        every { com.jonjonesbr.audiobookgen.service.GuidedReadingService.ativo } returns true
        every { anyConstructed<AppPrefs>().motorTts } returns motor
        every { anyConstructed<AppPrefs>().vozSelecionada } returns voz
        every { anyConstructed<AppPrefs>().ritmo } returns 100
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) } returns false
        every { anyConstructed<MediaPlayerEngineImpl>().play(any(), any()) } just Runs
        val manager = createManager()
        try {
            manager.setParagraphs(listOf(atual, seguinte).map {
                Paragrafo(texto = it, charOffset = 0, indiceCapitulo = 0)
            })
            manager.play(0)
            // IO real apenas para o falso sintetizador; bombeia Main até o áudio começar.
            val limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!manager.state.value.isPlaying && System.nanoTime() < limite) {
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(1)
            }
            assertTrue("O parágrafo atual deve estar tocando", manager.state.value.isPlaying)
            testDispatcher.scheduler.runCurrent()
            // Sem callback de conclusão: o próximo deve ser preparado durante o atual.
            assertTrue("Prefetch deve começar antes do fim do parágrafo único",
                prefetchIniciado.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, manager.state.value.index)
            // Avança enquanto o prefetch continua bloqueado: não pode duplicar a síntese.
            lastCompletionListener()
            testDispatcher.scheduler.runCurrent()
            terminarPrefetch.complete(Unit)
            val limiteSeguinte = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!manager.state.value.isPlaying && System.nanoTime() < limiteSeguinte) {
                testDispatcher.scheduler.runCurrent()
                Thread.sleep(1)
            }
            assertTrue("O próximo parágrafo deve tocar após o prefetch", manager.state.value.isPlaying)
            assertEquals(1, manager.state.value.index)
            io.mockk.coVerify(exactly = 1) { sintetizador.synthesize(seguinte, voz, 100) }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }
    // --- Helpers de reflexão para os poucos campos privados sem seam público ---

    private fun GuidedPlayerManager.field(name: String) =
        GuidedPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun GuidedPlayerManager.setCurrentIndex(index: Int) {
        field("currentIndex").set(this, index)
    }

    private fun GuidedPlayerManager.getCurrentIndex(): Int =
        field("currentIndex").get(this) as Int

    private fun GuidedPlayerManager.setPrefetchJob(job: Job?) {
        field("prefetchJob").set(this, job)
    }

    private fun GuidedPlayerManager.getPrefetchJob(): Job? =
        field("prefetchJob").get(this) as Job?

    private fun GuidedPlayerManager.prefetchedPathsSize(): Int =
        (field("prefetchedPaths").get(this) as Map<*, *>).size

    @Suppress("UNCHECKED_CAST")
    private fun GuidedPlayerManager.mutableState(): MutableStateFlow<GuidedState> =
        field("_state").get(this) as MutableStateFlow<GuidedState>

    private fun GuidedPlayerManager.profundidadePrefetchEfetiva(padraoDoMotor: Int): Int {
        val m = GuidedPlayerManager::class.java
            .getDeclaredMethod("profundidadePrefetchEfetiva", Int::class.java)
            .apply { isAccessible = true }
        return m.invoke(this, padraoDoMotor) as Int
    }

    // --- 1. resume()/pause() limpam o campo error do estado ---

    @Test
    fun pauseClearsErrorFromState() {
        val manager = createManager()
        lastErrorListener("Falha simulada de reprodução")
        assertEquals("Falha simulada de reprodução", manager.state.value.error)

        manager.pause()

        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.isPlaying)
    }

    @Test
    fun resumeClearsErrorFromStateWhenCurrentIndexIsValid() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        lastErrorListener("Falha simulada de reprodução")
        assertEquals("Falha simulada de reprodução", manager.state.value.error)

        manager.resume()

        assertNull(manager.state.value.error)
        assertTrue(manager.state.value.isPlaying)
    }

    @Test
    fun resumeIsNoOpAndKeepsErrorWhenCurrentIndexIsOutOfBounds() {
        val manager = createManager()
        // Sem setParagraphs()/play() prévios, currentIndex permanece -1 (fora dos bounds).
        lastErrorListener("Falha simulada de reprodução")

        manager.resume()

        // resume() não passa no guard de bounds — não deve alterar isPlaying nem limpar o error.
        assertEquals("Falha simulada de reprodução", manager.state.value.error)
        assertFalse(manager.state.value.isPlaying)
    }

    // --- 2. sessionToken de uma instância antiga não afeta mais o comportamento
    //         após uma nova instância ser criada (eventos tardios não disparam ações) ---

    @Test
    fun lateStateFromReplacedManagerDoesNotReachSharedBridge() {
        val managerA = createManager()
        GuidedPlaybackBridge.activate(managerA, "trackA", "pathA")
        testDispatcher.scheduler.runCurrent()

        managerA.mutableState().value = GuidedState(index = 2, isPlaying = true, isLoading = false)
        testDispatcher.scheduler.runCurrent()
        assertEquals(2, GuidedPlaybackBridge.activeState.value?.index)

        val managerB = createManager()
        GuidedPlaybackBridge.activate(managerB, "trackB", "pathB")
        testDispatcher.scheduler.runCurrent()

        assertNotEquals(managerA.sessionToken, managerB.sessionToken)
        // managerB acabou de ser ativado com index=-1 (estado inicial) — a ponte reflete isso.
        assertNull(GuidedPlaybackBridge.activeState.value)

        // Evento tardio de A (sessão substituída): seu coroutineScope segue rodando (nunca foi
        // stop()/release()), mas o guard `GuidedPlaybackBridge.manager === this` no init{} de
        // GuidedPlayerManager agora falha, então não deve mais alterar o estado compartilhado.
        managerA.mutableState().value = GuidedState(index = 9, isPlaying = true, isLoading = false)
        testDispatcher.scheduler.runCurrent()

        assertNull(GuidedPlaybackBridge.activeState.value)
    }

    // --- 3. setParagraphs() reseta currentIndex e cancela prefetchJob em andamento
    //         quando o conteúdo muda ---

    @Test
    fun setParagraphsResetsCurrentIndexAndCancelsPrefetchJobOnContentChange() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(3))
        manager.setCurrentIndex(1)
        val inFlightPrefetch = Job()
        manager.setPrefetchJob(inFlightPrefetch)

        manager.setParagraphs(paragraphs(5)) // tamanho diferente (3 -> 5) já basta para não ser "mesmo conteúdo"

        assertTrue(inFlightPrefetch.isCancelled)
        assertNull(manager.getPrefetchJob())
        assertEquals(-1, manager.getCurrentIndex())
        assertEquals(0, manager.prefetchedPathsSize())
    }

    @Test
    fun setParagraphsKeepsCurrentIndexAndPrefetchJobWhenContentIsUnchanged() {
        val manager = createManager()
        val original = paragraphs(3)
        manager.setParagraphs(original)
        manager.setCurrentIndex(1)
        val inFlightPrefetch = Job()
        manager.setPrefetchJob(inFlightPrefetch)

        // Mesmo conteúdo (mesmo texto por índice) — ex.: reabrir o leitor no mesmo documento.
        manager.setParagraphs(original.map { it.copy() })

        assertFalse(inFlightPrefetch.isCancelled)
        assertEquals(inFlightPrefetch, manager.getPrefetchJob())
        assertEquals(1, manager.getCurrentIndex())
    }

    // --- Buffering configurável (Ajustes › Buffering da leitura guiada): o override do
    //     usuário substitui o padrão de CADA motor; automático preserva o padrão de sempre ---

    @Test
    fun profundidadePrefetchEfetivaUsaPadraoDoMotorQuandoAutomatico() {
        every { anyConstructed<AppPrefs>().bufferParagrafosAdiante } returns AppPrefs.BUFFER_ADIANTE_AUTOMATICO
        val manager = createManager()

        assertEquals(5, manager.profundidadePrefetchEfetiva(5))
    }

    @Test
    fun profundidadePrefetchEfetivaUsaOverrideDoUsuarioQuandoConfigurado() {
        every { anyConstructed<AppPrefs>().bufferParagrafosAdiante } returns 7
        val manager = createManager()

        assertEquals(7, manager.profundidadePrefetchEfetiva(5))
    }

    // --- Pré-aquecimento do kokoro (init{}): nunca toca o outro motores; consulta a
    //     prontidão do bundle só quando o motor efetivo É kokoro ---

    @Test
    fun preWarmNeverChecksKokoroModelWhenEngineIsNotKokoro() {
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { anyConstructed<AppPrefs>().motorTts } returns "edge"
        every { anyConstructed<AppPrefs>().vozSelecionada } returns "edge-pt-BR-ThalitaNeural"

        createManager()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) }
    }

    @Test
    fun preWarmChecksKokoroModelReadinessWhenEngineIsKokoro() {
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) } returns false
        every { anyConstructed<AppPrefs>().motorTts } returns "kokoro"
        every { anyConstructed<AppPrefs>().vozSelecionada } returns "kokoro-pf-dora"

        createManager()
        testDispatcher.scheduler.runCurrent()

        // Bundle não pronto (mock) — barra ANTES de tentar construir o engine/tocar o
        // processo :sherpa (sem download automático, contrato do KokoroModelManager).
        verify(exactly = 1) { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) }
    }

    // --- 4. GuidedState.livroConcluido distingue fim natural do livro de stop() manual —
    //         consumido pela leitura sequencial da fila (QueueActivity "Ler tudo") pra saber
    //         quando avançar pro próximo arquivo sem confundir com o usuário fechando a
    //         guiada no meio do livro ---

    @Test
    fun nextAtLastParagraphMarksStateAsLivroConcluido() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(1) // último parágrafo (índice 1 de 2)

        manager.next()

        assertEquals(-1, manager.state.value.index)
        assertTrue(manager.state.value.livroConcluido)
    }

    @Test
    fun manualStopDoesNotMarkLivroConcluido() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)

        manager.stop()

        assertEquals(-1, manager.state.value.index)
        assertFalse(manager.state.value.livroConcluido)
    }
}
