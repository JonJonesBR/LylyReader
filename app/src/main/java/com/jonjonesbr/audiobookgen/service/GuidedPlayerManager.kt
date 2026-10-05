package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.domain.FalaNoTrecho
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.PerfilAudioLivro
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionResult
import com.jonjonesbr.audiobookgen.util.DivisorDeOracoes
import com.jonjonesbr.audiobookgen.util.OracaoGuia
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.StatsStore
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.VoiceAssignedSegment
import com.jonjonesbr.audiobookgen.util.TtsErrorMapper
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.player.AudioPlayerEngine
import com.jonjonesbr.audiobookgen.player.MediaPlayerEngineImpl
import com.jonjonesbr.audiobookgen.player.ExoPlayerEngineImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class GuidedState(
    val index: Int,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val progressPct: Float = 0f,
    val error: String? = null,
    // True só quando index == -1 pelo LIVRO TER ACABADO (último parágrafo concluído em
    // next()) — distingue do usuário fechar a guiada manualmente (botão fechar, sair da
    // tela), que também zera pra -1 mas com livroConcluido = false. Consumido pela leitura
    // sequencial da fila (ver ReaderActivity.EXTRA_FILA_SEQUENCIA) pra saber quando avançar
    // pro próximo arquivo sem confundir com uma parada manual do usuário.
    val livroConcluido: Boolean = false
)

private data class PrefetchedAudio(
    val path: String,
    val voice: String,
    val speedPct: Int,
    val textHash: Int
)

class GuidedPlayerManager(
    private val context: Context,
    private val pythonUseCase: PythonEngineUseCase
) {
    companion object {
        // Motores TTS Android de terceiros costumam ser mais frágeis sob rajada de chamadas
        // consecutivas — janela de prefetch mais rasa e uma pausa entre elas dá folga a eles.
        private const val PREFETCH_COUNT_ANDROID = 3
        private const val PAUSA_ENTRE_PREFETCH_ANDROID_MS = 300L
        private const val PROGRESS_POLL_INTERVAL_MS = 50L
        private const val MS_POR_MINUTO = 60_000L

        private const val PREFETCH_COUNT_ONNX = 5
        private const val PREFETCH_COUNT_ONLINE = 6
        // Profundidade da janela de prefetch de orações (kokoro e onnx/Supertonic) — casada
        // com o limite de concorrência do SherpaProcessClient (Semaphore(2)) no kokoro; no
        // onnx, o SupertonicProcessClient serializa por mutex (backend nativo nunca validado
        // pra chamadas concorrentes), então a 2ª posição só fica na fila — sem ganho de
        // paralelismo, mas sem risco, e o RTF do Supertonic (~0,57) já sobra folga.
        private const val PROFUNDIDADE_PREFETCH_ORACOES = 2
        private const val ESPERA_PRERENDER_MS = 1_000L
        private const val ESPERA_PAUSA_MS = 100L
        private const val LIMITE_TOM_TOTAL_MEIOS_TONS = 12
        private const val MAX_FALHAS_PRERENDER = 5
        // Quantas orações ANTES da última o handoff pro próximo parágrafo é disparado (ver
        // uso em tocarParagrafoPorOracoes) — 2 dá ~2 orações de folga extra de parede pro
        // kokoro/onnx/Pocket sintetizarem a virada antes do parágrafo atual acabar, sem disputar
        // demais pelo Semaphore(2) do :sherpa com o prefetch dentro do próprio parágrafo.
        private const val LEAD_ORACOES_PROXIMO_PARAGRAFO = 2
        /** `ritmo` é percentual (100 = normal) — converte para multiplicador de velocidade. */
        private const val RITMO_BASE = 100f
        // Texto mínimo e descartável só para forçar o carregamento da sessão ORT do kokoro
        // (ver preAquecerKokoroSeConfigurado) — nunca chega ao usuário.
        private const val TEXTO_PRE_AQUECIMENTO_KOKORO = "Preparando a leitura guiada."

        // Incrementa a cada instância — identifica de forma única cada sessão de leitura
        // guiada (um novo livro ou uma reinicialização do manager gera um token novo), para
        // observadores assíncronos (SleepTimerManager, GuidedReadingService) distinguirem
        // eventos de uma sessão já substituída dos da sessão vigente.
        private val tokenGenerator = java.util.concurrent.atomic.AtomicLong(0)
    }

    /** Identifica esta instância/sessão de forma única — ver uso em [GuidedPlaybackBridge]. */
    val sessionToken: Long = tokenGenerator.incrementAndGet()

    private val TAG = "GuidedPlayerManager"

    private val appPrefs = AppPrefs(context)
    
    private var playerEngine: AudioPlayerEngine? = null
    private var paragraphs: List<Paragrafo> = emptyList()
    @Volatile private var speakerAttribution: SpeakerAttributionResult? = null
    
    private var currentIndex = -1
    // Multiplicador de velocidade ao vivo (controlado na leitura guiada), persistido.
    private var speedMultiplier: Float = appPrefs.guidedSpeedMult
    private var currentBaseSpeed: Float = 1.0f
    private var msOuvidoAcumulado = 0L

    // Voz/motor específicos do livro em leitura (perfil por documento, ver
    // GuidedBookPrefsStore) — têm prioridade sobre AppPrefs.vozSelecionada/motorTts (globais)
    // quando presentes; null cai no fallback global de sempre.
    private var vozOverride: String? = null
    private var motorOverride: String? = null

    val playbackSpeedMultiplier: Float get() = speedMultiplier

    /** Ajusta a velocidade de reprodução ao vivo (aplica já no parágrafo atual). */
    fun setSpeedMultiplier(mult: Float) {
        speedMultiplier = mult
        appPrefs.guidedSpeedMult = mult
        try { playerEngine?.setPlaybackSpeed(currentBaseSpeed * mult) } catch (_: Exception) {}
        salvarPrefsDoLivro()
    }

    /** Registra a voz escolhida pelo usuário para o livro em leitura (perfil por documento). */
    fun registrarVozEscolhida(vozId: String, motor: String) {
        cancelarPreparo()
        vozOverride = vozId
        motorOverride = motor
        salvarPrefsDoLivro()
    }

    // Salva sempre a voz/motor EFETIVOS (override do livro, se houver, senão o global vigente
    // no momento) — assim um ajuste só de velocidade (sem nunca trocar de voz) ainda grava um
    // perfil completo e coerente para o livro, em vez de exigir as duas escolhas antes de salvar.
    private fun salvarPrefsDoLivro() {
        if (caminhoArquivo.isBlank()) return
        val (voz, motor) = vozEMotorAtuais()
        GuidedBookPrefsStore.save(context, caminhoArquivo, voz, motor, speedMultiplier)
    }

    /** Voz/motor efetivos para a síntese: perfil do livro, com fallback nas prefs globais. */
    private fun vozEMotorAtuais(): Pair<String, String> {
        val motor = motorOverride ?: appPrefs.motorTts
        val voz = vozOverride ?: appPrefs.vozSelecionada ?: VoiceCatalog.defaultFor(motor).id
        return voz to motor
    }

    /** Voz/motor do narrador para este livro (perfil "Voz e velocidade deste livro" ou global). */
    fun vozEMotorDoLivro(): Pair<String, String> = vozEMotorAtuais()

    /** Profundidade de prefetch efetiva (Ajustes › Buffering da leitura guiada): usa o
     *  override do usuário quando presente; senão, [padraoDoMotor] (comportamento de
     *  sempre, sem mudança até o usuário mexer nessa configuração). */
    private fun profundidadePrefetchEfetiva(padraoDoMotor: Int): Int {
        val override = ajustesDeAudio().bufferAdiante
        return if (override == AppPrefs.BUFFER_ADIANTE_AUTOMATICO) padraoDoMotor else override
    }

    // Nome exibido na notificação da leitura guiada (definido pela Activity)
    var trackName: String = "Leitura guiada"
    // Caminho do documento em leitura (definido pela Activity), propagado ao bridge junto
    // com o token/trackName na mesma escrita — ver GuidedPlaybackBridge.activate(). Carrega o
    // perfil de voz/velocidade salvo para este livro, se houver (T4.3).
    var caminhoArquivo: String = ""
        set(value) {
            field = value
            val prefsLivro = GuidedBookPrefsStore.load(context, value)
            vozOverride = prefsLivro?.voz
            motorOverride = prefsLivro?.motor
            speedMultiplier = prefsLivro?.speedMult ?: appPrefs.guidedSpeedMult
            perfilAudio = GuidedBookAudioStore.load(context, value)
            fixarTimbre(timbreAtual())
        }

    // Ajustes de áudio só deste livro ("Só neste livro" em Voz e motor); null usa os globais.
    private var perfilAudio: PerfilAudioLivro? = null

    /** Ajustes de áudio em vigor para este livro: o perfil dele, ou os globais de Configurações › Áudio. */
    fun ajustesDeAudio(): PerfilAudioLivro = perfilAudio ?: PerfilAudioLivro(
        tomMeiosTons = appPrefs.tomVozMeiosTons,
        agudosDb = appPrefs.suavizarAgudosDb,
        tomNasFalasMeiosTons = appPrefs.tomNasFalasMeiosTons,
        pausaFinalFraseMs = appPrefs.pausaFinalFraseMs,
        bufferAdiante = appPrefs.bufferParagrafosAdiante
    )

    fun temPerfilDeAudio(): Boolean = perfilAudio != null

    /** `null` apaga o perfil do livro (volta aos ajustes globais). Aplica o timbre na hora. */
    fun definirPerfilDeAudio(perfil: PerfilAudioLivro?) {
        perfilAudio = perfil
        GuidedBookAudioStore.save(context, caminhoArquivo, perfil)
        aplicarTimbre()
    }
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _state = MutableStateFlow(GuidedState(-1, isPlaying = false, isLoading = false))
    val state: StateFlow<GuidedState> = _state.asStateFlow()

    private var progressJob: Job? = null

    private val prefetchMutex = kotlinx.coroutines.sync.Mutex()
    private var playJob: Job? = null
    private var prefetchJob: Job? = null

    // Cache para o engine ONNX — evita recriar sessões a cada parágrafo
    private var onnxEngine: com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine? = null

    private fun getOnnxEngine(): com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine {
        if (onnxEngine == null) {
            onnxEngine = com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine(context)
        }
        return onnxEngine!!
    }

    private fun usaPipelineDeOracoes(engine: String): Boolean =
        engine == "kokoro" || engine == "onnx" || engine == "pocket"

    // Cache to hold pre-fetched paths for fast access.
    // ConcurrentHashMap: play() lê e setParagraphs() limpa fora do prefetchMutex,
    // enquanto prefetch() escreve em thread de IO dentro do mutex — um HashMap comum
    // sob leitura/escrita concorrente por threads diferentes tem comportamento indefinido.
    private val prefetchedPaths = java.util.concurrent.ConcurrentHashMap<Int, PrefetchedAudio>()

    // ── Modo oração (motores locais: kokoro, onnx/Supertonic e Pocket) ──────────
    // O parágrafo toca como sequência de orações (DivisorDeOracoes): cada oração é
    // sintetizada com a PRÓXIMA já em andamento, então a primeira voz chega depois de
    // ~1 oração de síntese e o silêncio entre orações fica ~0 — em vez de esperar o
    // parágrafo inteiro (dezenas de segundos quando o RTF do motor é > 1, como no
    // kokoro). No onnx/Supertonic e Pocket (RTF abaixo de 1 nos aparelhos testados), o mesmo modo
    // serve pra narração contínua por frase com pausa configurável (pausaFinalFraseMs)
    // entre elas, em vez de blocos de parágrafo inteiro — pedido explícito do usuário.
    // O estado/UI (índice, destaque, "N de M") continuam por PARÁGRAFO; o callback de
    // fim de áudio é roteado para o loop de orações enquanto uma sequência está ativa.
    @Volatile private var modoOracoes = false
    // Pausa pedida pelo usuário. Com vozes mais lentas que a fala (Kokoro) o laço espera a síntese
    // entre uma unidade e outra; se o usuário pausava nessa espera, o player já estava parado e a
    // unidade seguinte começava a tocar assim que ficava pronta, como se a pausa não existisse.
    @Volatile private var pausadoPeloUsuario = false
    // A pausa aconteceu sem áudio tocando (esperando síntese): retomar não deve chamar o player,
    // que reiniciaria a unidade anterior já terminada.
    @Volatile private var pausadoSemAudio = false
    // Identifica a reprodução corrente. play() incrementa; a limpeza no `finally` de uma
    // reprodução CANCELADA (que só roda quando a síntese em andamento devolve o controle, às vezes
    // segundos depois) não pode apagar o estado da reprodução nova — era o que fazia o destaque
    // correr até o fim do parágrafo e as orações seguintes perderem o prefetch ao trocar de voz.
    private var geracaoDeReproducao = 0
    @Volatile private var oracaoCompletada: CompletableDeferred<Unit>? = null
    // Posição da oração → job de síntese em andamento. Substituiu um único `Job?` (1 à
    // frente) por um mapa pra suportar janela de profundidade 2 (2 sínteses concorrentes no
    // :sherpa — Session.Run() do ONNX Runtime é thread-safe pra chamadas concorrentes na
    // MESMA sessão; validado empiricamente: ~1,66x de throughput vs sequencial, sem crash,
    // sem corrupção — ver PLANO_MELHORIAS_V6_PROGRESSO). Só é lido/escrito dentro do
    // coroutineScope (Dispatchers.Main) — não precisa ser concurrent-safe.
    private val pendingOracaoJobs = mutableMapOf<Int, Job>()
    private var proximoParagrafoJob: Job? = null
    private var alvoProximoParagrafo: Triple<Int, String, Int>? = null
    // Progresso de TEXTO dentro do parágrafo (oração atual → mapeamento para chars).
    private var oracaoCharsInicio = 0
    private var oracaoChars = 0
    private var paragrafoChars = 1
    // Cache descartável das orações do parágrafo corrente (pré-síntese 1 à frente).
    private val cacheOracoes = java.util.concurrent.ConcurrentHashMap<Int, PrefetchedAudio>()
    // 1ª e 2ª orações do PRÓXIMO parágrafo pré-sintetizadas durante a última oração do atual
    // (remove a espera de síntese na virada de parágrafo). Validade conferida por texto+voz+ritmo.
    // A 2ª existe pra fechar o déficit inicial do kokoro: sem ela, a 2ª oração do parágrafo só
    // começa a sintetizar quando a 1ª começa a TOCAR (cabeça de vantagem = duração da 1ª, uns
    // poucos segundos) — insuficiente com RTF > 1, gerando uma pausa perceptível logo na 2ª
    // oração antes do pipeline "pegar o ritmo" (relato real: 1ª frase rápida, pausa longa,
    // resto do bloco lido em sequência). Pré-sintetizar as duas em paralelo durante a última
    // oração do parágrafo ANTERIOR (janela ociosa — nada mais compete pelas 2 vagas concorrentes
    // do :sherpa nesse momento) dá à 2ª a mesma cabeça de vantagem que a 1ª já tinha.
    private var primeiraOracaoProximoParagrafo: PrefetchedAudio? = null
    private var segundaOracaoProximoParagrafo: PrefetchedAudio? = null

    init {
        initializeEngine()
        preAquecerKokoroSeConfigurado()
        // Espelha o estado deste manager (quando ativo) para o bridge, alimentando os
        // indicadores in-app fora da ReaderActivity. index == -1 → null (esconde o indicador).
        coroutineScope.launch {
            _state.collect { st ->
                if (GuidedPlaybackBridge.manager === this@GuidedPlayerManager) {
                    GuidedPlaybackBridge.activeState.value = if (st.index >= 0) st else null
                }
            }
        }
        // Fade-out nos últimos segundos da soneca, em vez de corte seco de volume — só quando
        // esta é a sessão vigente (evita instância substituída mexer no player ativo de outra).
        coroutineScope.launch {
            SleepTimerManager.volumeFactor.collect { fator ->
                if (GuidedPlaybackBridge.manager === this@GuidedPlayerManager) {
                    try { playerEngine?.setVolume(fator) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun timbreAtual() = ajustesDeAudio().let {
        com.jonjonesbr.audiobookgen.player.TimbreVoz(it.tomMeiosTons, it.agudosDb)
    }

    private var timbreAplicado: com.jonjonesbr.audiobookgen.player.TimbreVoz? = null

    /**
     * Timbre da oração/parágrafo prestes a tocar: o do livro, mais o "tom nas falas" quando o trecho é fala
     * ou citação (o narrador "faz a voz" do personagem). [partes] vem da análise do parágrafo, null = sem fala.
     */
    private fun aplicarTimbreDoTrecho(partes: List<OfflineSpeakerAttributor.ParagraphPart>?, ini: Int, tamanho: Int, total: Int) {
        val ajustes = ajustesDeAudio()
        var timbre = timbreAtual()
        if (ajustes.tomNasFalasMeiosTons != 0 && partes != null && FalaNoTrecho.ehFala(partes, ini, tamanho, total)) {
            timbre = timbre.copy(
                meiosTons = (timbre.meiosTons + ajustes.tomNasFalasMeiosTons)
                    .coerceIn(-LIMITE_TOM_TOTAL_MEIOS_TONS, LIMITE_TOM_TOTAL_MEIOS_TONS)
            )
        }
        if (timbre != timbreAplicado) fixarTimbre(timbre)
    }

    private fun fixarTimbre(timbre: com.jonjonesbr.audiobookgen.player.TimbreVoz) {
        timbreAplicado = timbre
        playerEngine?.setTimbre(timbre)
    }

    private fun partesParaTomDasFalas(paragrafo: String): List<OfflineSpeakerAttributor.ParagraphPart>? =
        if (ajustesDeAudio().tomNasFalasMeiosTons == 0) null else OfflineSpeakerAttributor.partsOf(paragrafo)

    /** Aplica já o timbre salvo nas preferências ao áudio em reprodução (diálogo de timbre). */
    fun aplicarTimbre() {
        fixarTimbre(timbreAtual())
    }

    private fun initializeEngine() {
        playerEngine?.release()
        val engineChoice = appPrefs.playerEngine
        Log.d(TAG, "Initializing player engine: $engineChoice")
        playerEngine = if (engineChoice == "exoplayer") {
            ExoPlayerEngineImpl(context)
        } else {
            MediaPlayerEngineImpl(context)
        }
        fixarTimbre(timbreAtual())
        playerEngine?.setCompletionListener {
            // Modo oração: o fim do áudio da oração acorda o loop para tocar a próxima
            // (em vez de avançar o parágrafo, que só acontece com a sequência completa).
            val completarOracao = oracaoCompletada
            if (modoOracoes && completarOracao != null) {
                completarOracao.complete(Unit)
            } else {
                onPlaybackCompleted()
            }
        }
        playerEngine?.setErrorListener { error ->
            Log.e(TAG, "Player error: $error")
            CrashLogWriter.log(null, "GuidedPlayerManager.playerError", message = error)
            stopProgressPolling()
            _state.update { it.copy(isPlaying = false, isLoading = false, progressPct = 0f, error = error) }
        }
    }

    /**
     * Kokoro carrega uma sessão ORT de ~114MB no processo `:sherpa` só na 1ª síntese — sem
     * isso, o primeiro play() da leitura guiada paga esse custo (bind ao processo + carregar
     * o modelo) no caminho crítico, e o usuário percebe como demora pra começar a tocar.
     * Dispara em background uma síntese mínima descartável assim que o manager é criado
     * (ReaderActivity abre bem antes do usuário apertar play, dando tempo de sobra pro
     * carregamento terminar antes do 1º play() real). Só roda quando a voz/motor
     * configurados no momento JÁ SÃO kokoro e o bundle já foi baixado — nunca dispara
     * download (contrato do KokoroModelManager) nem toca nos outros motores (edge/gemini/
     * onnx/elevenlabs/android inalterados). Qualquer falha aqui é silenciosa: a 1ª síntese
     * real tenta normalmente e reporta o erro de verdade, se houver.
     */
    private fun preAquecerKokoroSeConfigurado() {
        coroutineScope.launch {
            val (voz, motor) = vozEMotorAtuais()
            if (VoiceCatalog.effectiveEngine(voz, motor) != "kokoro") return@launch
            if (!com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(context)) return@launch
            withContext(Dispatchers.IO) {
                val tmp = File(context.cacheDir, "kokoro_preaquecimento_${System.nanoTime()}.wav")
                try {
                    com.jonjonesbr.audiobookgen.tts.KokoroTtsEngine(context)
                        .synthesize(TEXTO_PRE_AQUECIMENTO_KOKORO, voz, tmp)
                } catch (e: Exception) {
                    Log.w(TAG, "Pré-aquecimento do kokoro falhou (sem impacto na síntese real)", e)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = coroutineScope.launch {
            while (isActive) {
                val engine = playerEngine
                if (engine != null && engine.isPlaying()) {
                    val pos = engine.getCurrentPosition()
                    val dur = engine.getDuration()
                    if (dur > 0) {
                        val pctBruto = pos.toFloat() / dur.toFloat()
                        // No modo oração, o arquivo tocando é só um TRECHO do parágrafo:
                        // mapeia o progresso do áudio para a posição de TEXTO dentro do
                        // parágrafo inteiro (oracaoCharsInicio/oracaoChars), pro destaque/
                        // scroll da leitura guiada acompanharem sem "pular" entre trechos.
                        val pct = if (modoOracoes) {
                            val base = oracaoCharsInicio.toFloat() / paragrafoChars
                            base + (oracaoChars.toFloat() / paragrafoChars) * pctBruto
                        } else {
                            pctBruto
                        }
                        _state.update { it.copy(progressPct = pct.coerceIn(0f, 1f)) }
                    }
                    registrarTickDeEscuta()
                }
                delay(PROGRESS_POLL_INTERVAL_MS) // 20fps updates
            }
        }
    }

    /** Acumula tempo realmente tocando (tick de polling) e credita minutos inteiros nas estatísticas. */
    private fun registrarTickDeEscuta() {
        msOuvidoAcumulado += PROGRESS_POLL_INTERVAL_MS
        if (msOuvidoAcumulado >= MS_POR_MINUTO) {
            StatsStore.registrarMinutosOuvidos(context, (msOuvidoAcumulado / MS_POR_MINUTO).toInt())
            msOuvidoAcumulado %= MS_POR_MINUTO
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
        _state.update { it.copy(progressPct = 0f) }
    }

    fun setParagraphs(newParagraphs: List<Paragrafo>) {
        // Idempotente: se o conteúdo é o mesmo (ex.: reconexão ao reabrir o leitor), apenas
        // atualiza as referências sem limpar o prefetch já em andamento nem o índice atual.
        val mesmoConteudo = newParagraphs.size == paragraphs.size &&
            newParagraphs.indices.all { newParagraphs[it].texto == paragraphs[it].texto }
        if (!mesmoConteudo) {
            // Um prefetch em andamento lê `paragraphs`/índices da lista antiga (dentro do
            // prefetchMutex) — sem cancelar aqui, ele segue rodando sobre a lista nova assim
            // que ela for atribuída abaixo, podendo estourar bounds ou gravar cache com
            // texto/índice que não correspondem mais ao conteúdo vigente.
            prefetchJob?.cancel()
            prefetchJob = null
            prefetchedPaths.clear()
            // currentIndex apontava para um índice da lista antiga; não faz sentido preservá-lo
            // sobre o conteúdo novo — os guards de bounds em resume()/next()/previous() cobrem
            // o caso defensivo, mas o reset aqui evita depender só deles.
            currentIndex = -1
            // Sem isso, minutos acumulados do livro anterior eram creditados às estatísticas
            // do livro novo assim que o tick de escuta cruzasse o próximo minuto inteiro.
            msOuvidoAcumulado = 0L
        }
        this.paragraphs = newParagraphs
    }

    fun setSpeakerAttribution(result: SpeakerAttributionResult?) {
        speakerAttribution = result
    }

    // Fluxo sequencial coeso (setup -> síntese -> playback -> erro); só cresceu 3 linhas por
    // causa do log de diagnóstico de falha silenciosa, não vale fragmentar agora.
    @Suppress("LongMethod")
    fun play(index: Int) {
        if (paragraphs.isEmpty()) return
        if (index < 0 || index >= paragraphs.size) return
        
        currentIndex = index
        pausadoPeloUsuario = false
        pausadoSemAudio = false
        geracaoDeReproducao++
        // Zera já o estado da sequência anterior: a nova assume o controle agora.
        modoOracoes = false
        oracaoCompletada = null
        _state.update { GuidedState(index, isPlaying = false, isLoading = true) }

        // Mantém o processo vivo em 2º plano e exibe a notificação de mídia (#4).
        // activate() publica manager+token+trackName+caminho numa única escrita atômica.
        GuidedPlaybackBridge.activate(this, trackName, caminhoArquivo)
        // O estado de "foreground ativo" mora no próprio serviço (companion volátil), não
        // numa instância do manager — cada troca de livro cria um GuidedPlayerManager novo,
        // e um campo de instância aqui resetaria implicitamente a cada troca, disparando
        // start() de novo mesmo com o serviço já rodando. Ver GuidedReadingService.ativo.
        if (!GuidedReadingService.ativo) {
            GuidedReadingService.start(context)
        }

        // Re-initialize engine in case of engine preferences change
        initializeEngine()

        // Cancela o prefetch anterior para não competir por CPU/lock ONNX durante a síntese atual.
        prefetchJob?.cancel()
        pendingOracaoJobs.values.forEach { it.cancel() }
        pendingOracaoJobs.clear()

        playJob?.cancel()
        playJob = coroutineScope.launch {
            val ritmo = appPrefs.ritmo
            val speedVal = ritmo / RITMO_BASE

            val (voz, motor) = vozEMotorAtuais()

            val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)
            val synthesizer = com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
                motorEfetivo, context, pythonUseCase
            ) { getOnnxEngine() }

            try {
                val paragraphText = paragraphs[index].texto
                val voicePlan = speakerAttribution?.voicePlan(index, voz)
                if (!voicePlan.isNullOrEmpty() &&
                    voicePlan.size > 1 && voicePlan.any { it.voiceId != voz }
                ) {
                    tocarParagrafoPorFalantes(
                        segments = voicePlan,
                        paragraphText = paragraphText,
                        narratorVoice = voz,
                        fallbackEngine = motorEfetivo,
                        ritmo = ritmo,
                        speedVal = speedVal
                    )
                    return@launch
                }
                // O handoff pode ainda estar sintetizando quando o áudio anterior acaba.
                // Consome esse lote antes de consultar os caches, sem duplicar a síntese
                // nem cancelar a segunda oração ao disparar o handoff seguinte.
                if (usaPipelineDeOracoes(motorEfetivo) &&
                    alvoProximoParagrafo == Triple(index, voz, ritmo)
                ) {
                    proximoParagrafoJob?.join()
                }

                // Toca o parágrafo como sequência de orações sintetizadas em pipeline (a
                // seguinte sintetiza enquanto a atual toca) em vez de esperar o parágrafo
                // inteiro. Kokoro (RTF > 1) precisa disso pra não ficar em silêncio por
                // dezenas de segundos entre blocos; Supertonic (RTF ~0,57, mais rápido que
                // tempo real) não tem esse déficit, mas o pedido do usuário é a narração
                // contínua por frase (com pausa configurável entre elas — pausaFinalFraseMs)
                // em vez de blocos de parágrafo inteiro. Orações muito curtas são agrupadas
                // na seguinte (rajada de unidades minúsculas deixava o pipeline sem áudio
                // pronto — silêncio a cada ponto final).
                val oracoes = if (usaPipelineDeOracoes(motorEfetivo)) {
                    DivisorDeOracoes.agruparOracoesCurta(
                        DivisorDeOracoes.dividirEmOracoes(paragraphText)
                    )
                } else {
                    emptyList()
                }
                if (oracoes.size > 1) {
                    // Consome a 1ª oração pré-sintetizada durante o parágrafo ANTERIOR (se a
                    // voz/ritmo/texto ainda conferem) — a virada de parágrafo não espera síntese.
                    val primeiro = oracoes.first()
                    val prefetchAnterior = primeiraOracaoProximoParagrafo
                    if (prefetchAnterior != null &&
                        prefetchAnterior.voice == voz &&
                        prefetchAnterior.speedPct == ritmo &&
                        prefetchAnterior.textHash == primeiro.texto.hashCode()
                    ) {
                        cacheOracoes[0] = prefetchAnterior
                        primeiraOracaoProximoParagrafo = null
                        Log.d(TAG, "Usando 1ª oração pré-sintetizada do parágrafo $index")
                    }
                    val segundo = oracoes.getOrNull(1)
                    val prefetchSegundoAnterior = segundaOracaoProximoParagrafo
                    if (segundo != null && prefetchSegundoAnterior != null &&
                        prefetchSegundoAnterior.voice == voz &&
                        prefetchSegundoAnterior.speedPct == ritmo &&
                        prefetchSegundoAnterior.textHash == segundo.texto.hashCode()
                    ) {
                        cacheOracoes[1] = prefetchSegundoAnterior
                        segundaOracaoProximoParagrafo = null
                        Log.d(TAG, "Usando 2ª oração pré-sintetizada do parágrafo $index")
                    }
                    tocarParagrafoPorOracoes(
                        oracoes = oracoes,
                        paragraphText = paragraphText,
                        voz = voz,
                        ritmo = ritmo,
                        speedVal = speedVal,
                        synthesizer = synthesizer
                    )
                    return@launch
                }

                val textHash = paragraphText.hashCode()
                val cachedPrefetch = prefetchedPaths[index]
                    ?.takeIf { it.voice == voz && it.speedPct == ritmo && it.textHash == textHash }
                    ?.path

                val audioPath = cachedPrefetch ?: withContext(Dispatchers.IO) {
                    synthesizer.synthesize(paragraphText, voz, ritmo)
                }

                if (audioPath != null) {
                    val audioFile = File(audioPath)
                    if (audioFile.exists()) {
                        Log.d(TAG, "Playing paragraph $index: $audioPath")
                        currentBaseSpeed = speedVal
                        aguardarRetomada()
                        aplicarTimbreDoTrecho(partesParaTomDasFalas(paragraphText), 0, paragraphText.length, paragraphText.length)
                        playerEngine?.play(audioFile, speedVal * speedMultiplier)
                        _state.update { GuidedState(index, isPlaying = true, isLoading = false) }
                        startProgressPolling()
                        
                        // Unidade única local não passa pelo loop de orações: inicia aqui
                        // o mesmo handoff, aproveitando o áudio atual para preparar o próximo.
                        if (usaPipelineDeOracoes(motorEfetivo)) {
                            prefetchProximaOracaoDoParagrafoSeguinte(voz, ritmo, synthesizer)
                        } else {
                            prefetch(index + 1)
                        }
                    } else {
                        _state.update {
                            GuidedState(
                                index,
                                isPlaying = false,
                                isLoading = false,
                                error = "Arquivo de áudio não encontrado."
                            )
                        }
                    }
                } else {
                    // synthesize() retornou null sem lançar exceção (ex.: timeout do motor,
                    // voz não encontrada) — registra mesmo assim, senão a causa raiz só aparece
                    // no logcat e nunca chega ao arquivo de log que o usuário consegue enviar.
                    val motivo = synthesizer.ultimoErro() ?: "motivo desconhecido"
                    CrashLogWriter.log(
                        null, "play",
                        "Falha ao sintetizar: index=$index, voz=$voz, motor=$motorEfetivo, motivo=$motivo"
                    )
                    _state.update {
                        GuidedState(
                            index, isPlaying = false, isLoading = false,
                            error = TtsErrorMapper.toUserMessage(motivo)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in play workflow", e)
                CrashLogWriter.log(e, "play", "index=$index, voz=$voz, motor=$motorEfetivo")
                _state.update { GuidedState(index, isPlaying = false, isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Toca as falas de um parágrafo com as vozes atribuídas, preparando o próximo segmento
     * enquanto o atual toca. O índice e o destaque continuam no nível do parágrafo.
     */
    private suspend fun tocarParagrafoPorFalantes(
        segments: List<VoiceAssignedSegment>,
        paragraphText: String,
        narratorVoice: String,
        fallbackEngine: String,
        ritmo: Int,
        speedVal: Float
    ) {
        currentCoroutineContext().ensureActive()
        val minhaGeracao = geracaoDeReproducao
        modoOracoes = true
        paragrafoChars = paragraphText.length.coerceAtLeast(1)
        val narrador = com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
            fallbackEngine, context, pythonUseCase
        ) { getOnnxEngine() }
        var nextSynthesis: kotlinx.coroutines.Deferred<String?>? = null
        var textCursor = 0
        try {
            for ((position, segment) in segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val engine = VoiceCatalog.effectiveEngine(segment.voiceId, fallbackEngine)
                val synthesizer = if (segment.voiceId == narratorVoice) narrador else
                    com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
                        engine, context, pythonUseCase
                    ) { getOnnxEngine() }

                var audioPath = nextSynthesis?.await()
                nextSynthesis = null
                if (audioPath == null) {
                    audioPath = withContext(Dispatchers.IO) {
                        synthesizer.synthesize(segment.text, segment.voiceId, ritmo)
                    }
                }

                val next = segments.getOrNull(position + 1)
                if (next != null) {
                    val nextEngine = VoiceCatalog.effectiveEngine(next.voiceId, fallbackEngine)
                    val nextSynthesizer = if (next.voiceId == narratorVoice) narrador else
                        com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
                            nextEngine, context, pythonUseCase
                        ) { getOnnxEngine() }
                    nextSynthesis = coroutineScope.async(Dispatchers.IO) {
                        nextSynthesizer.synthesize(next.text, next.voiceId, ritmo)
                    }
                }

                if (audioPath == null && segment.voiceId != narratorVoice) {
                    Log.w(TAG, "Voz do personagem indisponível; usando narrador para o segmento $position")
                    audioPath = withContext(Dispatchers.IO) {
                        narrador.synthesize(segment.text, narratorVoice, ritmo)
                    }
                }
                val audioFile = audioPath?.let(::File)
                if (audioFile == null || !audioFile.exists()) {
                    val motivo = synthesizer.ultimoErro() ?: narrador.ultimoErro() ?: "motivo desconhecido"
                    CrashLogWriter.log(
                        null,
                        "play.speaker_segments",
                        "Falha ao sintetizar segmento $position/${segments.size}, voz=${segment.voiceId}, motivo=$motivo"
                    )
                    _state.update {
                        GuidedState(
                            currentIndex,
                            isPlaying = false,
                            isLoading = false,
                            error = TtsErrorMapper.toUserMessage(motivo)
                        )
                    }
                    return
                }

                val foundAt = paragraphText.indexOf(segment.text, textCursor)
                oracaoCharsInicio = if (foundAt >= 0) foundAt else textCursor
                oracaoChars = segment.text.length
                textCursor = (oracaoCharsInicio + oracaoChars).coerceAtMost(paragraphText.length)
                aguardarRetomada()
                oracaoCompletada = CompletableDeferred()
                aplicarTimbreDoTrecho(null, 0, 0, 1) // vozes de personagem: só o timbre do livro
                playerEngine?.play(audioFile, speedVal * speedMultiplier)
                if (position == 0) {
                    currentBaseSpeed = speedVal
                    _state.update { GuidedState(currentIndex, isPlaying = true, isLoading = false) }
                    startProgressPolling()
                }
                oracaoCompletada?.await()
            }
        } finally {
            nextSynthesis?.cancel()
            if (minhaGeracao == geracaoDeReproducao) {
                modoOracoes = false
                oracaoCompletada = null
            }
        }
        onPlaybackCompleted()
    }

    /**
     * Pré-sintetiza a 1ª (e a 2ª, se houver) unidade do parágrafo seguinte durante a última
     * oração do atual. Parágrafos de unidade única são pré-sintetizados inteiros no cache de
     * parágrafos (o play do próximo usa o caminho único existente); parágrafos multi-unidade
     * têm a 1ª/2ª oração guardadas em [primeiraOracaoProximoParagrafo]/
     * [segundaOracaoProximoParagrafo] e consumidas no play seguinte (validade por
     * voz+ritmo+hash do texto). As duas sintetizam em PARALELO (:sherpa aceita 2 sínteses
     * concorrentes e, nesta janela, nada mais disputa essas vagas) — dá à 2ª oração a mesma
     * cabeça de vantagem que a 1ª, evitando a pausa perceptível que ela teria se só começasse
     * a sintetizar quando a 1ª começasse a TOCAR (ver comentário em [segundaOracaoProximoParagrafo]).
     */
    private fun prefetchProximaOracaoDoParagrafoSeguinte(
        voz: String,
        ritmo: Int,
        synthesizer: com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
    ) {
        val proximo = currentIndex + 1
        if (proximo >= paragraphs.size) return
        proximoParagrafoJob?.cancel()
        alvoProximoParagrafo = Triple(proximo, voz, ritmo)
        proximoParagrafoJob = coroutineScope.launch {
            val texto = paragraphs[proximo].texto
            val unidades = DivisorDeOracoes.agruparOracoesCurta(
                DivisorDeOracoes.dividirEmOracoes(texto)
            )
            if (unidades.isEmpty()) return@launch
            if (unidades.size == 1) {
                val ph = texto.hashCode()
                val path = withContext(Dispatchers.IO) { synthesizer.synthesize(texto, voz, ritmo) }
                if (path != null) {
                    prefetchedPaths[proximo] = PrefetchedAudio(path, voz, ritmo, ph)
                    Log.d(TAG, "Parágrafo $proximo (unidade única) pré-sintetizado: $path")
                }
                return@launch
            }
            val primeiro = unidades[0]
            val segundo = unidades.getOrNull(1)
            val jobPrimeiro = async(Dispatchers.IO) { synthesizer.synthesize(primeiro.texto, voz, ritmo) }
            val jobSegundo = segundo?.let { async(Dispatchers.IO) { synthesizer.synthesize(it.texto, voz, ritmo) } }
            val pathPrimeiro = jobPrimeiro.await()
            if (pathPrimeiro != null) {
                primeiraOracaoProximoParagrafo = PrefetchedAudio(pathPrimeiro, voz, ritmo, primeiro.texto.hashCode())
                Log.d(TAG, "1ª oração do parágrafo $proximo pré-sintetizada: $pathPrimeiro")
            }
            val pathSegundo = jobSegundo?.await()
            if (pathSegundo != null && segundo != null) {
                segundaOracaoProximoParagrafo = PrefetchedAudio(pathSegundo, voz, ritmo, segundo.texto.hashCode())
                Log.d(TAG, "2ª oração do parágrafo $proximo pré-sintetizada: $pathSegundo")
            }
        }
    }

    /**
     * Toca o parágrafo corrente como sequência de orações, sintetizando cada oração com a
     * PRÓXIMA já em andamento (1 à frente). Resultado: a primeira voz chega depois de ~1
     * oração de síntese (segundos) e o silêncio entre orações fica ~0 mesmo com RTF > 1 —
     * em vez de esperar o parágrafo inteiro (dezenas de segundos com motor local lento, ex.
     * kokoro). No onnx/Supertonic (RTF < 1), o mesmo pipeline dá a narração contínua por
     * frase pedida pelo usuário, com a pausa entre orações configurável em Ajustes.
     *
     * Contrato com a UI: o estado ([GuidedState.index], destaque, "N de M") avança apenas
     * quando o PARÁGRAFO inteiro termina — a divisão em orações é só de síntese/playback.
     * O progresso reportado é mapeado para a posição de texto dentro do parágrafo (ver
     * [startProgressPolling]), então o destaque acompanha a leitura sem saltos por oração.
     */
    @Suppress("LongMethod")
    private suspend fun tocarParagrafoPorOracoes(
        oracoes: List<OracaoGuia>,
        paragraphText: String,
        voz: String,
        ritmo: Int,
        speedVal: Float,
        synthesizer: com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
    ) {
        currentCoroutineContext().ensureActive()
        val minhaGeracao = geracaoDeReproducao
        modoOracoes = true
        paragrafoChars = paragraphText.length.coerceAtLeast(1)
        val partesDasFalas = partesParaTomDasFalas(paragraphText)
        val totalDasOracoes = oracoes.sumOf { it.texto.length }.coerceAtLeast(1)
        try {
            for ((posicao, oracao) in oracoes.withIndex()) {
                currentCoroutineContext().ensureActive()

                val textHash = oracao.texto.hashCode()
                var audioPath = cacheOracoes[posicao]
                    ?.takeIf { it.voice == voz && it.speedPct == ritmo && it.textHash == textHash }
                    ?.path
                if (audioPath == null && posicao > 0) {
                    // O prefetch da oração atual foi disparado quando uma das ANTERIORES
                    // começou a tocar (janela de profundidade 2). Se ainda está em andamento,
                    // ESPERA ele concluir — nunca re-sintetiza em paralelo com o PRÓPRIO job
                    // desta posição: a re-síntese dobrava a espera nas orações curtas seguidas
                    // de longas (silêncio longo em cada ponto final).
                    aguardarPrefetchDaPosicao(posicao)
                    audioPath = cacheOracoes[posicao]
                        ?.takeIf { it.voice == voz && it.speedPct == ritmo && it.textHash == textHash }
                        ?.path
                }
                if (audioPath == null) {
                    // Só chega aqui se o prefetch falhou (ou posicao == 0 — primeira oração,
                    // sintetizada na hora): síntese direta como fallback.
                    audioPath = withContext(Dispatchers.IO) {
                        synthesizer.synthesize(oracao.texto, voz, ritmo)
                    }
                }

                if (audioPath == null) {
                    val motivo = synthesizer.ultimoErro() ?: "motivo desconhecido"
                    CrashLogWriter.log(
                        null, "play.oracoes",
                        "Falha ao sintetizar oração $posicao/${oracoes.size}: voz=$voz, motivo=$motivo"
                    )
                    _state.update {
                        GuidedState(
                            currentIndex, isPlaying = false, isLoading = false,
                            error = TtsErrorMapper.toUserMessage(motivo)
                        )
                    }
                    return
                }
                val audioFile = File(audioPath)
                if (!audioFile.exists()) {
                    _state.update {
                        GuidedState(
                            currentIndex, isPlaying = false, isLoading = false,
                            error = "Arquivo de áudio não encontrado."
                        )
                    }
                    return
                }

                oracaoCharsInicio = oracao.charsAcumulados
                oracaoChars = oracao.texto.length
                garantirPrefetchOracoes(posicao, oracoes, voz, ritmo, synthesizer)
                // Gatilho da pré-síntese do PRÓXIMO parágrafo antecipado em
                // LEAD_ORACOES_PROXIMO_PARAGRAFO orações (em vez de só na última) — medido em
                // device (2026-09-22): disparar só na última oração dava pouca folga pro
                // handoff terminar antes do parágrafo acabar quando a última oração era curta,
                // gerando pausas de 10-20s entre parágrafos (o mecanismo funcionava, só perdia
                // a corrida). Antecipar o gatilho dá mais tempo de parede pro kokoro/onnx/Pocket
                // sintetizarem o handoff enquanto o restante do parágrafo atual ainda toca.
                // Paragrafos curtos (poucas orações) disparam já na primeira posição (coerce a 0).
                val gatilhoProximoParagrafo = (oracoes.lastIndex - LEAD_ORACOES_PROXIMO_PARAGRAFO)
                    .coerceAtLeast(0)
                if (posicao == gatilhoProximoParagrafo) {
                    prefetchProximaOracaoDoParagrafoSeguinte(voz, ritmo, synthesizer)
                }

                Log.d(TAG, "Playing oração $posicao/${oracoes.size} (parágrafo $currentIndex): $audioPath")
                // Cria o CompletableDeferred ANTES de play() — mesma correção de corrida do
                // modo 1ª frase antecipada (ver nota lá): se play() completasse rápido o
                // bastante, o callback de conclusão veria oracaoCompletada ainda null.
                aguardarRetomada()
                oracaoCompletada = CompletableDeferred()
                aplicarTimbreDoTrecho(partesDasFalas, oracao.charsAcumulados, oracao.texto.length, totalDasOracoes)
                playerEngine?.play(audioFile, speedVal * speedMultiplier)
                if (posicao == 0) {
                    currentBaseSpeed = speedVal
                    _state.update { GuidedState(currentIndex, isPlaying = true, isLoading = false) }
                    startProgressPolling()
                }
                oracaoCompletada?.await()
                // Pausa editável (Ajustes) a cada ponto final — sem ela, orações consecutivas
                // tocavam coladas (o "gap" do KokoroTtsEngine só existe DENTRO de uma síntese
                // que precisou dividir por tamanho; cada oração aqui é um arquivo separado,
                // tocado sequencialmente pelo player, sem pausa nenhuma entre eles).
                val pausaMs = ajustesDeAudio().pausaFinalFraseMs
                if (pausaMs > 0) delay(pausaMs.toLong())
            }
        } finally {
            // Só limpa se esta ainda é a reprodução corrente (ver geracaoDeReproducao).
            if (minhaGeracao == geracaoDeReproducao) {
                modoOracoes = false
                oracaoCompletada = null
                pendingOracaoJobs.values.forEach { it.cancel() }
                pendingOracaoJobs.clear()
                cacheOracoes.clear()
            }
        }
        // Parágrafo concluído (todas as orações tocaram): avança como no fim do arquivo único.
        onPlaybackCompleted()
    }

    /** Aguarda o job de prefetch da oração [posicao], se ainda estiver em voo. */
    private suspend fun aguardarPrefetchDaPosicao(posicao: Int) {
        pendingOracaoJobs[posicao]?.join()
    }

    /**
     * Garante que as orações de `posicaoAtual+1` até `posicaoAtual+PROFUNDIDADE_PREFETCH_ORACOES`
     * estejam cacheadas ou com síntese já em andamento — janela de prefetch de profundidade 2
     * (em vez de 1): com RTF > 1 consistente (não só variância pontual), manter só 1 oração à
     * frente nunca fecha o déficit — o motor sempre está "correndo atrás". Rodar 2 sínteses
     * concorrentes no :sherpa aproveita núcleos ociosos (threads>4 não aceleram UMA síntese
     * neste hardware — ver ORT_THREADS_MAX no KokoroTtsEngine) e reduz o tempo de parede real
     * (validado empiricamente: ~1,66x). Nunca relança um job já em voo pra mesma posição.
     */
    private fun garantirPrefetchOracoes(
        posicaoAtual: Int,
        oracoes: List<OracaoGuia>,
        voz: String,
        ritmo: Int,
        synthesizer: com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
    ) {
        val ultimoAlvo = posicaoAtual + profundidadePrefetchEfetiva(PROFUNDIDADE_PREFETCH_ORACOES)
        val alvosPendentes = (posicaoAtual + 1..ultimoAlvo)
            .filter { it < oracoes.size && !pendingOracaoJobs.containsKey(it) }
        for (alvo in alvosPendentes) {
            val oracao = oracoes[alvo]
            val cached = cacheOracoes[alvo]
                ?.takeIf { it.voice == voz && it.speedPct == ritmo && it.textHash == oracao.texto.hashCode() }
            if (cached == null) {
                pendingOracaoJobs[alvo] = lancarPrefetchOracao(alvo, oracao, voz, ritmo, synthesizer)
            }
        }
    }

    /** Sintetiza a oração [alvo] em background e a registra em [cacheOracoes] ao concluir. */
    private fun lancarPrefetchOracao(
        alvo: Int,
        oracao: OracaoGuia,
        voz: String,
        ritmo: Int,
        synthesizer: com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
    ): Job = coroutineScope.launch {
        val path = withContext(Dispatchers.IO) { synthesizer.synthesize(oracao.texto, voz, ritmo) }
        if (path != null) {
            cacheOracoes[alvo] = PrefetchedAudio(path, voz, ritmo, oracao.texto.hashCode())
            Log.d(TAG, "Prefetch oração $alvo pronta: $path")
        } else {
            val motivo = synthesizer.ultimoErro() ?: "motivo desconhecido"
            CrashLogWriter.log(
                null, "prefetch.oracoes",
                "Falha ao pré-sintetizar oração $alvo: voz=$voz, motivo=$motivo"
            )
        }
        pendingOracaoJobs.remove(alvo)
    }

    fun pause() {
        stopProgressPolling()
        pausadoSemAudio = playerEngine?.isPlaying() != true
        pausadoPeloUsuario = true
        playerEngine?.pause()
        _state.update { it.copy(isPlaying = false, error = null) }
    }

    fun resume() {
        if (currentIndex >= 0 && currentIndex < paragraphs.size) {
            val estavaSemAudio = pausadoSemAudio
            pausadoPeloUsuario = false
            pausadoSemAudio = false
            if (!estavaSemAudio) playerEngine?.resume()
            _state.update { it.copy(isPlaying = true, error = null) }
            startProgressPolling()
        }
    }

    /** Segura o laço de reprodução enquanto o usuário mantém a pausa (não inicia a próxima unidade). */
    private suspend fun aguardarRetomada() {
        while (pausadoPeloUsuario) {
            currentCoroutineContext().ensureActive()
            delay(ESPERA_PAUSA_MS)
        }
    }

    fun stop(livroConcluido: Boolean = false) {
        pausadoPeloUsuario = false
        pausadoSemAudio = false
        stopProgressPolling()
        playJob?.cancel()
        prefetchJob?.cancel()
        pendingOracaoJobs.values.forEach { it.cancel() }
        pendingOracaoJobs.clear()
        proximoParagrafoJob?.cancel()
        proximoParagrafoJob = null
        alvoProximoParagrafo = null
        primeiraOracaoProximoParagrafo = null
        segundaOracaoProximoParagrafo = null
        modoOracoes = false
        oracaoCompletada = null
        playerEngine?.stop()
        currentIndex = -1
        if (GuidedPlaybackBridge.manager === this) {
            GuidedPlaybackBridge.deactivate(this)
            // Limpa explicitamente (com manager já nulo, o espelho do _state não dispararia).
            GuidedPlaybackBridge.activeState.value = null
        }
        // O GuidedReadingService encerra-se sozinho ao observar index == -1
        _state.update {
            GuidedState(-1, isPlaying = false, isLoading = false, progressPct = 0f, livroConcluido = livroConcluido)
        }
    }

    fun next() {
        if (currentIndex < paragraphs.size - 1) {
            play(currentIndex + 1)
        } else {
            if (caminhoArquivo.isNotBlank()) StatsStore.registrarLivroConcluido(context, caminhoArquivo)
            stop(livroConcluido = true)
        }
    }

    fun previous() {
        if (currentIndex > 0) {
            play(currentIndex - 1)
        }
    }

    private fun prefetch(index: Int) {
        if (paragraphs.isEmpty()) return
        if (index < 0 || index >= paragraphs.size) return

        val (vozPrefetch, motorPrefetch) = vozEMotorAtuais()
        val motorEfetivoPrefetch = VoiceCatalog.effectiveEngine(vozPrefetch, motorPrefetch)
        // Kokoro, onnx (Supertonic) e Pocket tocam por ORAÇÕES (pipeline dentro do parágrafo):
        // prefetch de parágrafo inteiro é contraproducente — a síntese longa do parágrafo
        // N+1 ocuparia o processo isolado (:sherpa/:supertonic) por segundos/dezenas de
        // segundos; cancelá-la no meio NÃO interrompe a síntese remota, que segue
        // bloqueando o engine (as orações do N+1 ficariam esperando um parágrafo órfão
        // terminar, ou — no onnx — disputando o mutex do SupertonicProcessClient).
        if (usaPipelineDeOracoes(motorEfetivoPrefetch)) return

        // Cancela o prefetch anterior (se ainda em andamento) para não empilhar sínteses
        // de índices já obsoletos quando o usuário troca de parágrafo rapidamente.
        prefetchJob?.cancel()
        prefetchJob = coroutineScope.launch {
            prefetchMutex.withLock {
                try {
                    val ritmo = appPrefs.ritmo
                    val (voz, motor) = vozEMotorAtuais()

                    val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)
                    val synthesizer = com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
                        motorEfetivo, context, pythonUseCase
                    ) { getOnnxEngine() }

                    // Janela de buffer adaptativa por tipo de motor:
                    //  • ONNX (Supertonic) é CPU-bound — sintetizar muito à frente
                    //    compete com o playback pela CPU (esquenta/gasta bateria) → janela modesta.
                    //  • Motores online (Edge/Gemini) são I/O-bound — janela mais funda absorve
                    //    latência de rede e mantém a leitura fluida.
                    //  • Android (motores TTS de terceiros instalados) — vários apps de TTS de
                    //    terceiros não aguentam uma rajada de chamadas consecutivas na mesma
                    //    conexão (passam a falhar depois de 2-3 sínteses seguidas) — janela mais
                    //    rasa e uma pequena pausa entre chamadas evita sobrecarregar o serviço.
                    // A síntese é sequencial (o caminho Python não é seguro p/ chamadas paralelas),
                    // mas como cada parágrafo toca por dezenas de segundos, dá tempo de encher o buffer.
                    // Em regime permanente repõe só 1 parágrafo por avanço, então a janela maior
                    // afeta só a rajada inicial e a resiliência — não a taxa média de CPU/bateria.
                    val prefetchCount = profundidadePrefetchEfetiva(
                        when (motorEfetivo) {
                            "onnx" -> PREFETCH_COUNT_ONNX
                            "android" -> PREFETCH_COUNT_ANDROID
                            else -> PREFETCH_COUNT_ONLINE
                        }
                    )
                    val lastIndex = minOf(index + prefetchCount - 1, paragraphs.lastIndex)

                    withContext(Dispatchers.IO) {
                        for (prefetchIndex in index..lastIndex) {
                            ensureActive()

                            val paragraphText = paragraphs[prefetchIndex].texto
                            val textHash = paragraphText.hashCode()
                            val cached = prefetchedPaths[prefetchIndex]
                                ?.takeIf { it.voice == voz && it.speedPct == ritmo && it.textHash == textHash }
                            if (cached != null) continue

                            Log.d(TAG, "Prefetching paragraph $prefetchIndex...")
                            val path = synthesizer.synthesize(paragraphText, voz, ritmo)
                            if (path != null) {
                                prefetchedPaths[prefetchIndex] = PrefetchedAudio(path, voz, ritmo, textHash)
                                Log.d(TAG, "Prefetch successful for paragraph $prefetchIndex: $path")
                            } else {
                                val motivo = synthesizer.ultimoErro() ?: "motivo desconhecido"
                                val msg = "Falha ao sintetizar: index=$prefetchIndex, voz=$voz, " +
                                    "motor=$motorEfetivo, motivo=$motivo"
                                CrashLogWriter.log(null, "prefetch", msg)
                            }
                            if (motorEfetivo == "android") delay(PAUSA_ENTRE_PREFETCH_ANDROID_MS)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Prefetch failed for paragraph $index", e)
                    CrashLogWriter.log(e, "prefetch", "index=$index")
                }
            }
        }
    }

    // ── Preparar capítulo (pré-renderização) ────────────────────────────────
    // Sintetiza com antecedência as unidades que a leitura guiada vai pedir (as MESMAS de play():
    // segmentos por personagem, orações ou parágrafo inteiro) e as deixa no cache de áudio em disco.
    // Depois disso a leitura só encontra arquivos prontos, sem esperar o motor. Vozes mais lentas que a
    // fala (Kokoro) não conseguem "ficar na frente" enquanto tocam, então o preparo serve para usar o
    // tempo parado (tela apagada, carregando): espera a leitura ao vivo terminar de tocar antes de seguir.
    private var preRenderJob: Job? = null

    /** A tela que iniciou o preparo já foi fechada: libera este gerenciador quando o preparo terminar. */
    @Volatile var liberarAoFimDoPreparo = false

    /** Unidades (texto, voz) que [play] sintetizaria para o parágrafo [index] com a voz [voz]. */
    private fun unidadesDoParagrafo(index: Int, voz: String, motorEfetivo: String): List<Pair<String, String>> {
        val texto = paragraphs[index].texto
        if (texto.none { it.isLetterOrDigit() }) return emptyList()
        val plano = speakerAttribution?.voicePlan(index, voz)
        if (!plano.isNullOrEmpty() && plano.size > 1 && plano.any { it.voiceId != voz }) {
            return plano.map { it.text to it.voiceId }
        }
        if (usaPipelineDeOracoes(motorEfetivo)) {
            val oracoes = DivisorDeOracoes.agruparOracoesCurta(DivisorDeOracoes.dividirEmOracoes(texto))
            if (oracoes.size > 1) return oracoes.map { it.texto to voz }
        }
        return listOf(texto to voz)
    }

    /** Quantas unidades o preparo de [desde]..[ate] sintetizaria (para estimar e confirmar com o usuário). */
    fun contarUnidadesParaPreparo(desde: Int, ate: Int): Int {
        if (paragraphs.isEmpty()) return 0
        val (voz, motor) = vozEMotorAtuais()
        val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)
        return (desde.coerceAtLeast(0)..ate.coerceAtMost(paragraphs.lastIndex))
            .sumOf { unidadesDoParagrafo(it, voz, motorEfetivo).size }
    }

    fun preparandoCapitulo(): Boolean = preRenderJob?.isActive == true

    fun cancelarPreparo() {
        val job = preRenderJob ?: return
        if (job.isActive) {
            job.cancel()
            PreRenderBridge.state.update { it?.copy(cancelado = true) }
        }
        preRenderJob = null
    }

    /** Começa a preparar os parágrafos [desde]..[ate]. [titulo] aparece na notificação. */
    fun prepararCapitulo(desde: Int, ate: Int, titulo: String) {
        if (paragraphs.isEmpty() || preRenderJob?.isActive == true) return
        val ritmo = appPrefs.ritmo
        val (voz, motor) = vozEMotorAtuais()
        val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)
        val indices = (desde.coerceAtLeast(0)..ate.coerceAtMost(paragraphs.lastIndex)).toList()
        val unidades = indices.flatMap { i -> unidadesDoParagrafo(i, voz, motorEfetivo).map { i to it } }
        if (unidades.isEmpty()) return
        val lento = motorEfetivo == "kokoro"

        PreRenderBridge.state.value = PreRenderState(0, unidades.size, titulo)
        PreRenderBridge.cancelar = { cancelarPreparo() }
        PreRenderService.start(context)

        preRenderJob = coroutineScope.launch {
            var feitos = 0
            var falhasSeguidas = 0
            val sintetizadores = mutableMapOf<String, com.jonjonesbr.audiobookgen.tts.TtsSynthesizer>()
            try {
                for ((_, unidade) in unidades) {
                    val (texto, vozUnidade) = unidade
                    // Voz ou ritmo mudaram no meio: o que já foi feito ficou com a chave antiga; para aqui.
                    if (vozEMotorAtuais().first != voz || appPrefs.ritmo != ritmo) {
                        PreRenderBridge.state.update { it?.copy(cancelado = true) }
                        return@launch
                    }
                    // Motor lento: não disputa a CPU com a leitura ao vivo.
                    while (lento && (_state.value.isPlaying || _state.value.isLoading)) {
                        PreRenderBridge.state.update { it?.copy(esperandoLeitura = true) }
                        delay(ESPERA_PRERENDER_MS)
                    }
                    PreRenderBridge.state.update { it?.copy(esperandoLeitura = false) }
                    val motorDaUnidade = VoiceCatalog.effectiveEngine(vozUnidade, motorEfetivo)
                    val sintetizador = sintetizadores.getOrPut(motorDaUnidade) {
                        com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(
                            motorDaUnidade, context, pythonUseCase
                        ) { getOnnxEngine() }
                    }
                    val caminho = withContext(Dispatchers.IO) { sintetizador.synthesize(texto, vozUnidade, ritmo) }
                    if (caminho == null) {
                        falhasSeguidas++
                        val motivo = sintetizador.ultimoErro() ?: "motivo desconhecido"
                        CrashLogWriter.log(null, "prerender", "Falha ao preparar trecho: voz=$vozUnidade, motivo=$motivo")
                        if (falhasSeguidas >= MAX_FALHAS_PRERENDER) {
                            PreRenderBridge.state.update {
                                it?.copy(erro = TtsErrorMapper.toUserMessage(motivo))
                            }
                            return@launch
                        }
                    } else {
                        falhasSeguidas = 0
                    }
                    feitos++
                    PreRenderBridge.state.update { it?.copy(feitos = feitos) }
                }
                PreRenderBridge.state.update { it?.copy(feitos = unidades.size, concluido = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Preparo do capítulo falhou", e)
                CrashLogWriter.log(e, "prerender", "voz=$voz")
                PreRenderBridge.state.update { it?.copy(erro = e.message ?: "erro") }
            } finally {
                if (liberarAoFimDoPreparo && _state.value.index < 0) {
                    preRenderJob = null
                    release()
                }
            }
        }
    }

    private fun onPlaybackCompleted() {
        Log.d(TAG, "Playback completed for paragraph $currentIndex")
        next()
    }

    fun release() {
        cancelarPreparo()
        stop()
        val onnx = onnxEngine
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { onnx?.releaseAll() } catch (_: Exception) {}
        }
        playerEngine?.release()
        playerEngine = null
        coroutineScope.cancel()
    }
}
