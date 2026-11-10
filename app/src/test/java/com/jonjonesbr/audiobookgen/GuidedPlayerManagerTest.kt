package com.jonjonesbr.audiobookgen

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.StatsStore
import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.service.GuidedBookAudioStore
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.SynthesisWakeLock
import com.jonjonesbr.audiobookgen.player.MediaPlayerEngineImpl
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
    private val textoTrechoNaoTocou = "Não foi possível tocar este trecho."
    // Relógio (elapsedRealtime) da leitura do trecho: o teste move o tempo para checar o instante publicado.
    private var relogioMs = 0L

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { relogioMs }
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkConstructor(AppPrefs::class)
        every { anyConstructed<AppPrefs>().guidedSpeedMult } returns 1.0f
        // Ritmo Fluida: as pausas ficam na faixa de antes (fator 1,0) e com a entonação contínua, como nos testes.
        every { anyConstructed<AppPrefs>().estiloDeRitmo } returns EstiloDeRitmo.FLUIDA

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

        // Fila "Ler tudo": estado do fim do livro (posição final e concluído), perfil do livro e wake lock.
        mockkObject(StatsStore, CrashLogWriter, SynthesisWakeLock, GuidedBookPrefsStore, GuidedBookAudioStore)
        every { StatsStore.registrarLivroConcluido(any(), any()) } just Runs
        every { StatsStore.registrarMinutosOuvidos(any(), any()) } just Runs
        every { CrashLogWriter.log(any(), any(), any(), any()) } just Runs
        every { SynthesisWakeLock.acquire(any()) } just Runs
        every { SynthesisWakeLock.release() } just Runs
        every { GuidedBookPrefsStore.load(any(), any()) } returns null
        every { GuidedBookPrefsStore.save(any(), any(), any(), any(), any()) } just Runs
        every { GuidedBookAudioStore.load(any(), any()) } returns null
        every { GuidedBookAudioStore.save(any(), any(), any()) } just Runs
        every { anyConstructed<AppPrefs>().posicaoLeitura(any(), any()) } returns -1
        every { anyConstructed<AppPrefs>().salvarPosicaoLeitura(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        createdManagers.forEach { runCatching { it.release() } }
        createdManagers.clear()
        GuidedPlaybackBridge.manager?.let { GuidedPlaybackBridge.deactivate(it) }
        GuidedPlaybackBridge.activeState.value = null
        // A soneca é global ao processo: a de um teste não pode vazar para o seguinte.
        SleepTimerManager.cancel()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createManager(): GuidedPlayerManager {
        val context = mockk<Context>(relaxed = true)
        // Falha do player: o estado leva a mensagem traduzida (o relaxed devolveria ""), não o texto cru do motor.
        every { context.getString(R.string.erro_guiada_trecho_nao_tocou) } returns textoTrechoNaoTocou
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
        every { com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para("edge", any(), any(), any(), any()) } returns synthesizer
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
            testDispatcher.scheduler.advanceTimeBy(2_000)
            aguardar { playerPlayCount() == 2 }
            io.mockk.coVerify(exactly = 1) {
                synthesizer.synthesize(", disse Marina.", "pt-BR-ThalitaMultilingualNeural", 100)
            }
            lastCompletionListener()
            testDispatcher.scheduler.advanceTimeBy(2_000)
            aguardar { manager.state.value.index == -1 }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    @Test
    fun pausaNaturalSeguraOAvancoEntreParagrafosTocadosInteiros() {
        val arquivo = java.io.File.createTempFile("guiada-pausa-", ".wav")
        val synthesizer = mockk<com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>()
        io.mockk.coEvery { synthesizer.synthesize(any(), any(), 100) } returns arquivo.absolutePath
        mockkObject(com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.Companion)
        every { com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para("edge", any(), any(), any(), any()) } returns synthesizer
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
            manager.setParagraphs(paragraphs(2))
            manager.play(0)
            aguardar { manager.state.value.isPlaying }

            lastCompletionListener()
            testDispatcher.scheduler.runCurrent()
            // Parágrafo curto: pausa natural de 0,5 s a 0,85 s antes do próximo.
            assertEquals(0, manager.state.value.index)
            testDispatcher.scheduler.advanceTimeBy(1_000)
            aguardar { manager.state.value.index == 1 }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    /**
     * Leitura de dois parágrafos (um capítulo cada) pelo motor "edge". O fim do parágrafo espera a pausa natural
     * de fim de parágrafo (ritmo Fluida), então quem termina um parágrafo avança depois de `advanceTimeBy(2_000)`.
     */
    private fun prepararLeituraEdge(arquivo: java.io.File) {
        val synthesizer = mockk<com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>()
        io.mockk.coEvery { synthesizer.synthesize(any(), any(), 100) } returns arquivo.absolutePath
        mockkObject(com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.Companion)
        every {
            com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para("edge", any(), any(), any(), any())
        } returns synthesizer
        mockkObject(com.jonjonesbr.audiobookgen.service.GuidedReadingService.Companion)
        every { com.jonjonesbr.audiobookgen.service.GuidedReadingService.ativo } returns true
        every { anyConstructed<AppPrefs>().motorTts } returns "edge"
        every { anyConstructed<AppPrefs>().vozSelecionada } returns "pt-BR-ThalitaMultilingualNeural"
        every { anyConstructed<AppPrefs>().ritmo } returns 100
        every { anyConstructed<MediaPlayerEngineImpl>().play(any(), any()) } just Runs
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) } returns false
    }

    @Test
    fun sonecaDeCapituloPausaNoFimDoCapituloEAvancaAoRetomar() {
        val arquivo = java.io.File.createTempFile("guiada-soneca-", ".wav")
        prepararLeituraEdge(arquivo)

        val manager = createManager()
        try {
            manager.setParagraphs(paragraphs(2))
            manager.play(0)
            aguardar { manager.state.value.isPlaying }

            SleepTimerManager.armarFimDoCapitulo(manager.sessionToken)
            lastCompletionListener()
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(2_000)

            // Terminou o último parágrafo do capítulo: pausa na fronteira, sem avançar, e a soneca foi consumida.
            assertEquals(0, manager.state.value.index)
            assertTrue(manager.estaPausado)
            assertFalse(SleepTimerManager.fimDoCapitulo.value)

            // Ao retomar, a leitura passa ao capítulo seguinte.
            manager.resume()
            testDispatcher.scheduler.advanceTimeBy(200)
            aguardar { manager.state.value.index == 1 }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    @Test
    fun sonecaDeCapituloDeOutraSessaoNaoSeguraOAvanco() {
        val arquivo = java.io.File.createTempFile("guiada-soneca-outra-", ".wav")
        prepararLeituraEdge(arquivo)

        val manager = createManager()
        try {
            manager.setParagraphs(paragraphs(2))
            manager.play(0)
            aguardar { manager.state.value.isPlaying }

            SleepTimerManager.armarFimDoCapitulo(manager.sessionToken + 1_000)
            lastCompletionListener()
            testDispatcher.scheduler.advanceTimeBy(2_000)
            testDispatcher.scheduler.runCurrent()

            aguardar { manager.state.value.index == 1 }
            assertFalse(manager.estaPausado)
            assertTrue(SleepTimerManager.fimDoCapitulo.value)
            // O avanço do próprio manager não re-atrela a soneca à sessão vigente nem deixa pergunta pendente.
            assertEquals(manager.sessionToken + 1_000, SleepTimerManager.armedToken)
            assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    /**
     * Soneca ligada no leitor de outro livro enquanto este toca no fundo: o avanço de parágrafo deste livro não
     * pode roubá-la nem perguntar nada, porque a sessão vigente não mudou. Depois, ao tocar o outro livro, a
     * soneca vale para ele sem pergunta (já está atrelada a ele).
     */
    @Test
    fun avancoDeLivroNoFundoNaoRoubaASonecaDeOutroLivro() {
        val arquivo = java.io.File.createTempFile("guiada-soneca-fundo-", ".wav")
        prepararLeituraEdge(arquivo)

        val tocando = createManager()
        val outro = createManager()
        try {
            tocando.setParagraphs(paragraphs(2))
            tocando.play(0)
            aguardar { tocando.state.value.isPlaying }

            outro.setParagraphs(paragraphs(2))
            SleepTimerManager.armarFimDoCapitulo(outro.sessionToken)

            lastCompletionListener()
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(2_000)

            aguardar { tocando.state.value.index == 1 }
            assertEquals(outro.sessionToken, SleepTimerManager.armedToken)
            assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)

            outro.play(0)
            assertEquals(outro.sessionToken, SleepTimerManager.armedToken)
            assertNull(SleepTimerManager.confirmacaoTrocaPendente.value)
        } finally {
            tocando.stop()
            outro.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    @Test
    fun playComSonecaDeOutraSessaoReatrelaEPergunta() {
        val arquivo = java.io.File.createTempFile("guiada-troca-soneca-", ".wav")
        prepararLeituraEdge(arquivo)

        val manager = createManager()
        try {
            manager.setParagraphs(paragraphs(2))
            SleepTimerManager.armarFimDoCapitulo(manager.sessionToken + 1_000)

            manager.play(0)

            assertEquals(manager.sessionToken, SleepTimerManager.armedToken)
            assertEquals(manager.sessionToken, SleepTimerManager.confirmacaoTrocaPendente.value)
            aguardar { manager.state.value.isPlaying }
        } finally {
            manager.stop()
            testDispatcher.scheduler.runCurrent()
            arquivo.delete()
        }
    }

    // --- Fila "Ler tudo": a troca de livro acontece na mesma sessão (sem stop() nem índice -1 entre os livros) ---

    private fun paragrafosDo(caminho: String, quantidade: Int = 2) =
        (0 until quantidade).map { Paragrafo(texto = "${caminho.trimStart('/')}-$it", charOffset = 0, indiceCapitulo = 0) }

    private fun livroDaFila(caminho: String, quantidade: Int = 2) = GuidedPlayerManager.LivroDaFila(
        caminho, "Título $caminho", TextoExtraido(emptyList(), paragrafosDo(caminho, quantidade), 0)
    )

    /** Manager lendo [caminho] no último parágrafo, com [fila] como os próximos livros. */
    private fun managerNoFimDoLivro(caminho: String, fila: List<String>): GuidedPlayerManager {
        // Os mesmos ajustes que os outros testes com play() fixam: o livro novo chega a tocar (ou a falhar) por eles.
        every { anyConstructed<AppPrefs>().motorTts } returns "edge"
        every { anyConstructed<AppPrefs>().vozSelecionada } returns "pt-BR-ThalitaMultilingualNeural"
        every { anyConstructed<AppPrefs>().ritmo } returns 100
        every { anyConstructed<AppPrefs>().pausaMs } returns 600
        mockkObject(com.jonjonesbr.audiobookgen.tts.KokoroModelManager)
        every { com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(any()) } returns false
        // Chaves do Gemini: a troca as lê ao sincronizar o motor (o cofre criptografado não existe no teste).
        mockkObject(com.jonjonesbr.audiobookgen.data.SecurePreferences)
        every { com.jonjonesbr.audiobookgen.data.SecurePreferences.getGeminiKeys(any()) } returns ""
        // O play() do livro novo não sintetiza de verdade: o motor devolve nada e a sessão só fica em erro, sem efeito.
        mockkObject(com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.Companion)
        every {
            com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(any(), any(), any(), any(), any())
        } returns mockk<com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>(relaxed = true)
        mockkObject(com.jonjonesbr.audiobookgen.service.GuidedReadingService.Companion)
        every { com.jonjonesbr.audiobookgen.service.GuidedReadingService.ativo } returns true
        val manager = createManager()
        manager.caminhoArquivo = caminho
        manager.setParagraphs(paragrafosDo(caminho))
        manager.filaSequencia = fila
        manager.setCurrentIndex(1)
        return manager
    }

    @Test
    fun fimDoLivroComFilaAbreOProximoSemPararASessao() {
        val manager = managerNoFimDoLivro("/a", listOf("/b"))
        manager.prepararLivroDaFila = { caminho -> livroDaFila(caminho) }
        val estados = mutableListOf<GuidedState>()

        manager.next()
        val coletor = CoroutineScope(testDispatcher).launch { manager.state.toList(estados) }
        testDispatcher.scheduler.runCurrent()
        coletor.cancel()

        assertEquals("/b", manager.caminhoArquivo)
        assertTrue(manager.filaSequencia.isEmpty())
        assertEquals(setOf("/a"), manager.livrosDeixadosNaFila)
        assertEquals(0, manager.state.value.index)
        assertFalse(manager.state.value.livroConcluido)
        assertTrue("A sessão não pode passar por índice -1 entre os livros", estados.none { it.index == -1 })
        // Posição final e conclusão do livro que acabou, gravadas pelo próprio manager.
        io.mockk.verify(exactly = 1) { anyConstructed<AppPrefs>().salvarPosicaoLeitura("/a", 1, 2) }
        io.mockk.verify(exactly = 1) { StatsStore.registrarLivroConcluido(any(), "/a") }
    }

    @Test
    fun livroQueNaoAbreEPuladoEOProximoToca() {
        val manager = managerNoFimDoLivro("/a", listOf("/b", "/c"))
        manager.prepararLivroDaFila = { caminho -> if (caminho == "/b") null else livroDaFila(caminho) }

        manager.next()
        testDispatcher.scheduler.runCurrent()

        assertEquals("/c", manager.caminhoArquivo)
        assertTrue(manager.filaSequencia.isEmpty())
        assertEquals(setOf("/a"), manager.livrosDeixadosNaFila)
        assertEquals(0, manager.state.value.index)
    }

    @Test
    fun seNenhumLivroDaFilaAbrirAGuiadaTerminaComoLivroConcluido() {
        val manager = managerNoFimDoLivro("/a", listOf("/b"))
        manager.prepararLivroDaFila = { null }

        manager.next()
        testDispatcher.scheduler.runCurrent()

        assertEquals(-1, manager.state.value.index)
        assertTrue(manager.state.value.livroConcluido)
        assertEquals("/a", manager.caminhoArquivo)
    }

    @Test
    fun stopDuranteATrocaCancelaOLivroQueSoPrepara() {
        val manager = managerNoFimDoLivro("/a", listOf("/b"))
        val liberar = CompletableDeferred<GuidedPlayerManager.LivroDaFila?>()
        manager.prepararLivroDaFila = { liberar.await() }

        manager.next()
        testDispatcher.scheduler.runCurrent()
        manager.stop()
        testDispatcher.scheduler.runCurrent()

        assertEquals("/a", manager.caminhoArquivo)
        assertEquals(listOf("/b"), manager.filaSequencia)
        assertFalse(manager.state.value.livroConcluido)
        assertEquals(-1, manager.state.value.index)
    }

    @Test
    fun pausaDuranteATrocaSeguraOLivroAteRetomar() {
        val manager = managerNoFimDoLivro("/a", listOf("/b"))
        val liberar = CompletableDeferred<GuidedPlayerManager.LivroDaFila?>()
        manager.prepararLivroDaFila = { liberar.await() }

        manager.next()
        testDispatcher.scheduler.runCurrent()
        manager.pause()
        liberar.complete(livroDaFila("/b"))
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(1_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals("/a", manager.caminhoArquivo)
        assertTrue(manager.trocandoDeLivro)

        manager.resume()
        testDispatcher.scheduler.advanceTimeBy(1_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals("/b", manager.caminhoArquivo)
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
        every { com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(motor, any(), any(), any(), any()) } returns sintetizador
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
            // Pausa natural de fim de parágrafo (sempre ligada): o relógio virtual a atravessa.
            testDispatcher.scheduler.advanceTimeBy(2_000)
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
    // rb-26b: com a guiada pausada, o estado zera o progresso (stopProgressPolling), mas a tela precisa saber qual
    // linha estava sendo narrada para levar a página até ela.
    @Test
    fun pausaGuardaOProgressoDoNarradoEZeraOEstado() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.6f)

        manager.pause()

        assertEquals(0f, manager.state.value.progressPct, 0f)
        assertEquals(0.6f, manager.progressoDoNarrado, 0f)
    }

    @Test
    fun retomarDepoisDaPausaSegueOEstado() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.6f)
        manager.pause()

        manager.resume()
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.3f)

        assertEquals(0.3f, manager.progressoDoNarrado, 0f)
    }

    @Test
    fun proximoDepoisDaPausaNaoDevolveOValorGuardado() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.6f)
        manager.pause()

        manager.next()

        assertNotEquals(0.6f, manager.progressoDoNarrado, 0f)
        assertEquals(manager.state.value.progressPct, manager.progressoDoNarrado, 0f)
    }

    @Test
    fun tocarOutroDepoisDaPausaNaoDevolveOValorGuardado() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.6f)
        manager.pause()

        manager.play(1)

        assertNotEquals(0.6f, manager.progressoDoNarrado, 0f)
        assertEquals(manager.state.value.progressPct, manager.progressoDoNarrado, 0f)
    }

    @Test
    fun duasPausasSeguidasMantemOValorGuardado() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.mutableState().value = manager.state.value.copy(progressPct = 0.6f)
        manager.pause()

        manager.pause() // soneca ou fone pausando de novo: não zera o valor guardado

        assertEquals(0.6f, manager.progressoDoNarrado, 0f)
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
        assertEquals(textoTrechoNaoTocou, manager.state.value.error)

        manager.pause()

        assertNull(manager.state.value.error)
        assertFalse(manager.state.value.isPlaying)
    }

    @Test
    fun resumeDepoisDaPausaVoltaATocarSemErro() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        manager.pause()

        manager.resume()

        assertNull(manager.state.value.error)
        assertTrue(manager.state.value.isPlaying)
    }

    @Test
    fun segundaPausaNaoImpedeRetomarOSom() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns true
        manager.pause() // o usuário pausa com o som tocando
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns false
        manager.pause() // soneca, fone ou foco de áudio pausam de novo

        manager.resume()

        // Antes a 2ª pausa marcava "pausado sem áudio" e o resume não retomava o player.
        verify(exactly = 1) { anyConstructed<MediaPlayerEngineImpl>().resume() }
    }

    // rb-24: o contador de comandos é a base de "nada mexeu na reprodução desde a pausa" (PausaParaOuvir).
    @Test
    fun pausarDuasVezesContaDoisComandos() {
        val manager = createManager()
        val antes = manager.comandosDeReproducao
        manager.pause()
        manager.pause() // a soneca que vence com a pausa já feita também conta

        assertEquals(antes + 2, manager.comandosDeReproducao)
    }

    @Test
    fun resumeContaUmComando() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        val antes = manager.comandosDeReproducao

        manager.resume()

        assertEquals(antes + 1, manager.comandosDeReproducao)
    }

    @Test
    fun resumeDepoisDeErroDoPlayerRecomecaOParagrafo() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(1)
        lastErrorListener("Falha simulada de reprodução")
        assertEquals(textoTrechoNaoTocou, manager.state.value.error)

        // Retomar um player que falhou não tocava nada (a tela mostrava "pausa" sem som): recomeça o parágrafo.
        manager.resume()

        assertNull(manager.state.value.error)
        assertEquals(1, manager.state.value.index)
        assertTrue(manager.state.value.isLoading)
        assertFalse(manager.state.value.isPlaying)
    }

    @Test
    fun resumeIsNoOpAndKeepsErrorWhenCurrentIndexIsOutOfBounds() {
        val manager = createManager()
        // Sem setParagraphs()/play() prévios, currentIndex permanece -1 (fora dos bounds).
        lastErrorListener("Falha simulada de reprodução")

        manager.resume()

        // resume() não passa no guard de bounds — não deve alterar isPlaying nem limpar o error.
        assertEquals(textoTrechoNaoTocou, manager.state.value.error)
        assertFalse(manager.state.value.isPlaying)
    }

    @Test
    fun nextSemLeituraEmCursoNaoConcluiNemRecomecaOLivro() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(3))
        // Sem play() prévio, currentIndex é -1: antes, next() tocava o 1º parágrafo.

        manager.next()

        assertEquals(-1, manager.getCurrentIndex())
        assertEquals(-1, manager.state.value.index)
        assertFalse(manager.state.value.isLoading)
        assertFalse(manager.state.value.livroConcluido)
    }

    @Test
    fun playDepoisDoReleaseNaoFicaPresoEmCarregando() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(3))
        manager.release()

        manager.play(0)

        assertFalse(manager.state.value.isLoading)
        assertEquals(-1, manager.state.value.index)
        assertNull(GuidedPlaybackBridge.manager)
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

    // --- 3. setParagraphs() reencontra o parágrafo atual e cancela prefetchJob em andamento
    //         quando o conteúdo muda ---

    @Test
    fun setParagraphsRemapsCurrentIndexAndCancelsPrefetchJobOnContentChange() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(3))
        manager.setCurrentIndex(1)
        val inFlightPrefetch = Job()
        manager.setPrefetchJob(inFlightPrefetch)

        // Texto recarregado com um parágrafo a mais no começo (ex.: bruto x limpo): "paragrafo-1" vai para o índice 2.
        manager.setParagraphs(
            listOf(Paragrafo(texto = "cabecalho", charOffset = 0, indiceCapitulo = 0)) + paragraphs(4)
        )

        assertTrue(inFlightPrefetch.isCancelled)
        assertNull(manager.getPrefetchJob())
        assertEquals(2, manager.getCurrentIndex())
        assertEquals(0, manager.prefetchedPathsSize())
    }

    @Test
    fun remapearIndiceUsaOMesmoTextoMaisProximoOuAProporcao() {
        val manager = createManager()
        assertEquals(3, manager.remapearIndice(listOf("a", "b", "c"), listOf("x", "a", "y", "b", "c"), 1))
        assertEquals(2, manager.remapearIndice(listOf("b", "z", "b"), listOf("b", "q", "b"), 2))
        // Parágrafo alterado: mesma proporção do livro (5/10 → 10/20).
        val antigos = (0 until 10).map { "p$it" }
        val novos = (0 until 20).map { "n$it" }
        assertEquals(10, manager.remapearIndice(antigos, novos, 5))
        assertEquals(-1, manager.remapearIndice(antigos, novos, -1))
        assertEquals(-1, manager.remapearIndice(antigos, emptyList(), 5))
    }

    @Test
    fun remapearIndiceReencontraOParagrafoQueALimpezaSoRetocou() {
        val manager = createManager()
        // Limpeza tirou um parágrafo-lixo e corrigiu espaços: acha pelo texto normalizado, não pela proporção.
        val limpo = listOf("Podemos continuar?", "Ele apontou para a bagunça.", "Voltei à consciência.", "Espera.")
        val bruto = listOf("12", "Podemos  continuar?", "Ele apontou para a  bagunça.", "Voltei à consciência.", "Espera.")
        assertEquals(2, manager.remapearIndice(limpo, bruto, 1))
        // Volta do bruto para o limpo: a âncora (texto que play() começou a ler) acerta mesmo se o bruto errou.
        assertEquals(1, manager.remapearIndice(bruto, limpo, 3, ancora = "Ele apontou para a bagunça."))
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
    fun profundidadePrefetchEfetivaIgnoraOOverrideAntigoEUsaOPadraoDoMotor() {
        // O pré-carregamento saiu da tela: um valor antigo de bufferParagrafosAdiante não vale mais.
        every { anyConstructed<AppPrefs>().bufferParagrafosAdiante } returns 7
        val manager = createManager()

        assertEquals(5, manager.profundidadePrefetchEfetiva(5))
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

    // --- rb-41: o trecho do player vai para a sessão de mídia só nos eventos (pausa, retomada, parada) ---

    /** Trecho de 8 s do motor, parado em 3 s, tocando; é o cenário dos testes da barra. */
    private fun prepararTrechoDe8s(manager: GuidedPlayerManager) {
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns true
        every { anyConstructed<MediaPlayerEngineImpl>().getDuration() } returns 8000
        every { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() } returns 3000
    }

    @Test
    fun pausaPublicaOTrechoParado() {
        val manager = createManager()
        prepararTrechoDe8s(manager)

        manager.pause()

        val trecho = manager.trechoNaSessao.value
        assertEquals(8000L, trecho?.duracaoMs)
        assertEquals(3000L, trecho?.posicaoMs)
        assertEquals(0f, trecho?.velocidade ?: -1f, 0f)
    }

    @Test
    fun retomarPublicaOTrechoTocando() {
        val manager = createManager()
        prepararTrechoDe8s(manager)
        manager.pause()

        manager.resume()

        val trecho = manager.trechoNaSessao.value
        assertEquals(8000L, trecho?.duracaoMs)
        assertEquals(3000L, trecho?.posicaoMs)
        assertEquals(1f, trecho?.velocidade ?: -1f, 0f)
    }

    @Test
    fun pausaNoVaoNaoVoltaABarraAoComeco() {
        val manager = createManager()
        manager.setParagraphs(paragraphs(2))
        manager.setCurrentIndex(0)
        // Tocando, com o trecho em 3 s; depois o motor responde parado e na posição 0.
        every { anyConstructed<MediaPlayerEngineImpl>().getDuration() } returns 8000
        every { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() } returns 3000
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns false
        manager.resume() // motor ainda sem som: o trecho sai como tocando, em 3 s
        manager.pause() // pausa sem áudio: o pause() do motor é chamado, mas a pausa fica marcada como sem áudio
        every { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() } returns 0

        manager.resume()

        // Sem áudio, retomar não chama o motor (que reiniciaria a unidade anterior) e a barra não volta ao começo.
        verify(exactly = 1) { anyConstructed<MediaPlayerEngineImpl>().resume() }
        assertEquals(3000L, manager.trechoNaSessao.value?.posicaoMs)
        assertEquals(0f, manager.trechoNaSessao.value?.velocidade ?: -1f, 0f)
    }

    @Test
    fun pararZeraOTrecho() {
        val manager = createManager()
        prepararTrechoDe8s(manager)
        manager.pause()
        assertEquals(8000L, manager.trechoNaSessao.value?.duracaoMs)

        manager.stop()

        assertNull(manager.trechoNaSessao.value)
    }

    @Test
    fun instanteDoTrechoEOMomentoEmQueASPosicaoFoiLida() {
        val manager = createManager()
        prepararTrechoDe8s(manager)
        relogioMs = 5_000L

        manager.pause()
        assertEquals(5_000L, manager.trechoNaSessao.value?.instanteMs)

        relogioMs = 9_000L
        manager.resume()
        assertEquals(9_000L, manager.trechoNaSessao.value?.instanteMs)
    }

    // O motor em Error posta MEDIA_ERROR a cada leitura, e o listener de erro voltaria a ler: o laço nunca parava.
    @Test
    fun erroDoPlayerNaoLeOMotorNemNaPausaSeguinte() {
        val manager = createManager()
        prepararTrechoDe8s(manager)

        lastErrorListener("Falha simulada de reprodução")
        verify(exactly = 0) { anyConstructed<MediaPlayerEngineImpl>().getDuration() }
        verify(exactly = 0) { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() }

        manager.pause()
        verify(exactly = 0) { anyConstructed<MediaPlayerEngineImpl>().getDuration() }
        verify(exactly = 0) { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() }
    }

    @Test
    fun erroCongelaOTrechoAnteriorComVelocidadeZero() {
        val manager = createManager()
        prepararTrechoDe8s(manager)
        manager.resume() // publica o trecho tocando: 8 s, parado em 3 s, na velocidade 1x

        lastErrorListener("Falha simulada de reprodução")

        val trecho = manager.trechoNaSessao.value
        assertEquals(8000L, trecho?.duracaoMs)
        assertEquals(3000L, trecho?.posicaoMs)
        assertEquals(0f, trecho?.velocidade ?: -1f, 0f)
    }
}
