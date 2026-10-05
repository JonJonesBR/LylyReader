package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R

import com.jonjonesbr.audiobookgen.domain.ExportStatus
import com.jonjonesbr.audiobookgen.domain.PreviewResult
import com.jonjonesbr.audiobookgen.service.ConversionNotificationManager
import com.jonjonesbr.audiobookgen.service.ParametrosConversao
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import com.jonjonesbr.audiobookgen.domain.PlayAudioUseCase
import com.jonjonesbr.audiobookgen.data.DocumentRepository
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.PlayerNotificationManager
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class ConversionDoneEvent {
    data class Sucesso(
        val caminho: String,
        val duracao: String,
        val tamanhoMb: String,
        val exportStatus: ExportStatus?,
        val runToken: String? = null
    ) : ConversionDoneEvent()
    data class Erro(val mensagem: String, val runToken: String? = null) : ConversionDoneEvent()
    data class Cancelada(val runToken: String? = null) : ConversionDoneEvent()
}


data class AudiobookUiState(
        // Processamento
        val processando: Boolean = false,
        val progressoPct: Int = 0,
        val progressoRunToken: String? = null,
        val statusMessage: String = "",
        val arquivoSelecionadoNome: String = "",
        val arquivoSelecionadoUri: Uri? = null,
        val pastaDestinoUri: Uri? = null,
        val ultimoAudioUri: Uri? = null,
        val exportStatus: ExportStatus? = null,
        val conversionDone: ConversionDoneEvent? = null,

        // Catálogos
        val vozesAtuais: List<Pair<String, String>> = emptyList(),
        val estilos: List<Pair<String, String>> = emptyList(),

        // Player
        val playerVisible: Boolean = false,
        val isPlaying: Boolean = false,
        val playerCurrentPosition: Int = 0,
        val playerDuration: Int = 0,
        val playerTrackName: String = "",
        val playerSpeed: Float = 1.0f,

        // Preview
        val amostraGerando: Boolean = false
)

class AudiobookViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** `ritmo` é percentual (100 = normal) — motores Android TTS esperam um multiplicador. */
        private const val RITMO_BASE = 100f
        /** Pausa padrão entre blocos da conversão (ms) — default do AppPrefs.pausaMs. */
        private const val PAUSA_MS_PADRAO = 600
    }

    private var pausaMsAtual: Int = PAUSA_MS_PADRAO
    private var observacaoJob: Job? = null

    private val _uiState = MutableStateFlow(AudiobookUiState())
    val uiState: StateFlow<AudiobookUiState> = _uiState.asStateFlow()

    // ── Catálogos Originais ──────────────────────────────────────────────────
    private val estilos =
            listOf(
                    "padrao" to " Padrão",
                    "terror" to " Terror",
                    "acao" to "⚡ Ação",
                    "scifi" to " Ficção Científica",
                    "drama" to " Drama",
                    "comedia" to " Comédia"
            )

    // Precisam ser declaradas antes do init{} abaixo: viewModelScope.launch usa
    // Dispatchers.Main.immediate, que roda a coroutine de forma síncrona quando já
    // estamos na main thread (sempre o caso na construção do ViewModel) — se essas
    // propriedades viessem depois do init{}, o corpo da coroutine rodaria antes delas
    // serem atribuídas, causando NullPointerException (bug real, não hipotético: já
    // aconteceu em produção).
    private val documentRepository = DocumentRepository(application)
    private val exportUseCase = DocumentExportUseCase(application)
    private val playerUseCase = PlayAudioUseCase(application)
    private val pythonEngine = PythonEngineUseCase(application)
    private val conversionNotif = ConversionNotificationManager(getApplication())

    init {
        _uiState.update { it.copy(estilos = estilos) }
        atualizarVozesPorMotor("edge")
        // Python NÃO é mais iniciado aqui de propósito — abrir a tela (MainActivity/
        // ReaderActivity, que instanciam este ViewModel) não deve custar o startup do
        // interpretador se o usuário só vai navegar/ler. Cada operação que realmente
        // precisa do Python (preview, conversão, extração de texto, capa) garante sua
        // própria inicialização via PythonEngineUseCase.inicializarMotorSeNecessario().
        viewModelScope.launch {
            PlayerStateHolder.state.collect { ps ->
                _uiState.update {
                    it.copy(
                        playerVisible = ps.isVisible,
                        isPlaying = ps.isPlaying,
                        playerCurrentPosition = ps.currentPosition,
                        playerDuration = ps.duration,
                        playerTrackName = ps.trackName,
                        playerSpeed = ps.speed
                    )
                }
            }
        }
        // Retoma observação se houver uma conversão em andamento (ex: app foi morto pelo sistema)
        viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(ConversionWorker.WORK_NAME)
                .filterNotNull()
                .collect { workInfos ->
                    val info = workInfos.firstOrNull() ?: return@collect
                    if ((info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED)
                        && !_uiState.value.processando) {
                        setProcessando(true)
                        _uiState.update { it.copy(statusMessage = getApplication<Application>().getString(R.string.status_conversion_running)) }
                        observarTrabalho(info.id)
                    }
                }
        }
    }

    // ── Gerenciamento de Estado ───────────────────────────────────────────────

    fun processarArquivoSelecionado(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val metadados = documentRepository.extrairMetadadosArquivo(uri)
            _uiState.update {
                it.copy(
                        arquivoSelecionadoUri = metadados.uri,
                        arquivoSelecionadoNome = metadados.nome
                )
            }
        }
    }

    fun registrarPastaDestino(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val metadados = documentRepository.processarPastaSaf(uri)
            _uiState.update { it.copy(pastaDestinoUri = metadados.uri) }
        }
    }

    fun atualizarVozesPorMotor(motor: String) {
        val lista = VoiceCatalog.forEngine(motor).map { it.id to it.displayLabel(getApplication()) }
        _uiState.update { it.copy(vozesAtuais = lista) }
    }

    // ── Python Use Cases (Substituindo Service e AsyncTask) ────────────────

    fun prepararConfiguracoesPython(motor: String, chaveEfetiva: String, pausaMs: Int) {
        pausaMsAtual = pausaMs
        viewModelScope.launch { pythonEngine.sincronizarConfiguracoes(motor, chaveEfetiva, pausaMs) }
    }

    // Despacha entre 3 caminhos de síntese (android/onnx/python) — cada um com seu próprio
    // fluxo de cache+erro; separar em funções menores fragmentaria a leitura do dispatch geral.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun gerarAmostra(voz: String, ritmo: Int, motor: String, chaveGemini: String, texto: String = "") {
        if (_uiState.value.amostraGerando) return
        _uiState.update { it.copy(amostraGerando = true, statusMessage = getApplication<Application>().getString(R.string.status_gerando_amostra)) }

        viewModelScope.launch {
            val velStr = com.jonjonesbr.audiobookgen.util.TtsRate.fromRitmo(ritmo)

            val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)

            if (motorEfetivo == "android") {
                val speed = ritmo / RITMO_BASE
                val textoDefault = "Hello! This is a sample text for the selected voice engine."
                val textoAmostra = texto.ifBlank { textoDefault }
                val cacheKey = "${voz.hashCode()}_${textoAmostra.hashCode()}"
                val outFile = java.io.File(
                    getApplication<android.app.Application>().cacheDir, "preview_android_$cacheKey.wav"
                )
                val result: Result<java.io.File> = if (outFile.exists() && outFile.length() > 0) {
                    Result.success(outFile)
                } else {
                    com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache.synthesize(
                        getApplication(), voz, textoAmostra, outFile, speed
                    )
                }
                if (result.isSuccess) {
                    _uiState.update { it.copy(amostraGerando = false, statusMessage = " Amostra Pronta") }
                    iniciarPreview(outFile.absolutePath)
                } else {
                    com.jonjonesbr.audiobookgen.util.CrashLogWriter.log(
                        result.exceptionOrNull(), "gerarAmostra", "voz=$voz"
                    )
                    val msg = "⚠ Falha ao gerar amostra com voz do dispositivo."
                    _uiState.update { it.copy(amostraGerando = false, statusMessage = msg) }
                }
                return@launch
            }

            // Bug #8: ONNX preview usa o engine Kotlin nativo diretamente (mais robusto)
            if (motorEfetivo == "onnx") {
                val caminho = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val engine = com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine(getApplication())
                    val textoDefault = "Hello! This is a sample text for the selected voice engine."
                    val textoAmostra = texto.ifBlank { textoDefault }
                    val cacheKey = "${voz.hashCode()}_${textoAmostra.hashCode()}"
                    val outFile = java.io.File(
                        getApplication<android.app.Application>().cacheDir,
                        "preview_onnx_$cacheKey.wav"
                    )
                    val result: Result<java.io.File> = if (outFile.exists() && outFile.length() > 0) {
                        Result.success(outFile)
                    } else {
                        engine.synthesize(textoAmostra, voz, outFile)
                    }
                    if (result.isSuccess) outFile.absolutePath else null
                }
                if (caminho != null) {
                    _uiState.update { it.copy(amostraGerando = false, statusMessage = " Amostra Pronta") }
                    iniciarPreview(caminho)
                } else {
                    _uiState.update {
                        it.copy(
                            amostraGerando = false,
                            statusMessage = getApplication<Application>().getString(R.string.status_amostra_onnx_falha)
                        )
                    }
                }
                return@launch
            }

            if (motorEfetivo == "kokoro" && VoiceCatalog.previewClipUrl(voz) == null) {
                // Vozes Piper/VITS (mesmo motor "kokoro") não têm clipe pré-renderizado: a amostra
                // é sintetizada localmente com o pacote já baixado (o seletor de vozes só libera o
                // ▶ depois do download).
                val app = getApplication<android.app.Application>()
                val sampleText = texto.ifBlank {
                    VoiceCatalog.findAny(voz)?.let(VoiceCatalog::previewSampleText)
                        ?: "Olá, esta é uma amostra da voz selecionada."
                }
                val synthesizer = com.jonjonesbr.audiobookgen.tts.KokoroTtsSynthesizer(app)
                val caminho = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    synthesizer.synthesize(sampleText, voz, ritmo)
                }
                if (caminho != null) {
                    _uiState.update {
                        it.copy(amostraGerando = false, statusMessage = " Amostra Pronta")
                    }
                    iniciarPreview(caminho)
                } else {
                    _uiState.update {
                        it.copy(
                            amostraGerando = false,
                            statusMessage = synthesizer.ultimoErro()
                                ?: app.getString(R.string.preview_kokoro_falha)
                        )
                    }
                }
                return@launch
            }

            if (motorEfetivo == "kokoro") {
                // Preview do Kokoro NÃO exige o bundle de ~350MB: baixa só o clipe
                // pré-renderizado (poucos KB, HF) — o usuário ouve a voz antes de
                // comprometer o download (V6 T1.5).
                val caminho = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = com.jonjonesbr.audiobookgen.util.VoiceCatalog.previewClipUrl(voz)
                        ?: return@withContext null
                    val app = getApplication<android.app.Application>()
                    val outFile = java.io.File(app.cacheDir, "preview_kokoro_${voz.hashCode()}.mp3")
                    if (!outFile.exists() || outFile.length() == 0L) {
                        val ok = kotlin.runCatching {
                            com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager
                                .downloadFileWithResume(url, outFile) { }
                        }.isSuccess
                        if (!ok) outFile.delete()
                    }
                    outFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
                }
                if (caminho != null) {
                    _uiState.update {
                        it.copy(amostraGerando = false, statusMessage = " Amostra Pronta")
                    }
                    iniciarPreview(caminho)
                } else {
                    _uiState.update {
                        it.copy(
                            amostraGerando = false,
                            statusMessage = getApplication<android.app.Application>()
                                .getString(R.string.preview_kokoro_falha)
                        )
                    }
                }
                return@launch
            }

            if (motorEfetivo == "pocket") {
                val app = getApplication<android.app.Application>()
                val sampleText = texto.ifBlank {
                    VoiceCatalog.findAny(voz)?.let(VoiceCatalog::previewSampleText)
                        ?: "Olá, esta é uma amostra da voz Pocket TTS."
                }
                val synthesizer = com.jonjonesbr.audiobookgen.tts.PocketTtsSynthesizer(app)
                val caminho = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    synthesizer.synthesize(sampleText, voz, ritmo)
                }
                if (caminho != null) {
                    _uiState.update {
                        it.copy(amostraGerando = false, statusMessage = " Amostra Pronta")
                    }
                    iniciarPreview(caminho)
                } else {
                    _uiState.update {
                        it.copy(
                            amostraGerando = false,
                            statusMessage = synthesizer.ultimoErro()
                                ?: app.getString(R.string.preview_pocket_failure)
                        )
                    }
                }
                return@launch
            }

            when (val result = pythonEngine.gerarPreviewVoz(voz, velStr, motorEfetivo, chaveGemini, texto)) {
                is PreviewResult.Success -> {
                    _uiState.update {
                        it.copy(amostraGerando = false, statusMessage = " Amostra Pronta")
                    }
                    iniciarPreview(result.caminho)
                }
                is PreviewResult.Error -> {
                    _uiState.update {
                        it.copy(amostraGerando = false, statusMessage = "⚠ ${result.mensagem}")
                    }
                }
            }
        }
    }


    /**
     * Monta os parâmetros de conversão (motor, voz, velocidade, pausa) a partir das preferências
     * salvas do usuário e dispara o processamento. Usado pela leitura guiada (ReaderActivity)
     * para converter um trecho/capítulo/livro sem que a Activity precise montar os parâmetros —
     * essa orquestração estava inline em ReaderActivity.iniciarDownloadBackground.
     */
    fun iniciarProcessamentoDeTexto(
        arquivoEntrada: String,
        nomeAudiobook: String,
        livroOrigemCaminho: String? = null,
        speakerMapPath: String? = null,
        /** Livro de onde vem o texto (inteiro ou um capítulo): sua voz, velocidade e tom valem na conversão. */
        caminhoDoLivro: String? = livroOrigemCaminho
    ) {
        val context = getApplication<Application>()
        val ajustes = ajustesDoLivro(caminhoDoLivro)
        val appPrefs = com.jonjonesbr.audiobookgen.data.AppPrefs(context)
        val chave = com.jonjonesbr.audiobookgen.data.SecurePreferences.getGeminiKeys(context)
        val saidaInterna = java.io.File(context.filesDir, "$nomeAudiobook.mp3").absolutePath

        prepararConfiguracoesPython(ajustes.motor, chave, appPrefs.pausaMs)
        iniciarProcessamento(
            ParametrosConversao(
                arquivoEntrada = arquivoEntrada,
                voz = ajustes.voz,
                velocidade = com.jonjonesbr.audiobookgen.util.TtsRate.fromRitmo(ajustes.ritmo),
                estilo = "padrao",
                caminhoSaidaInterna = saidaInterna,
                motor = ajustes.motor,
                livroOrigemCaminho = livroOrigemCaminho,
                speakerMapPath = speakerMapPath,
                tomMeiosTons = ajustes.tomMeiosTons,
                agudosDb = ajustes.agudosDb
            )
        )
    }

    /**
     * Converte o livro INTEIRO da biblioteca (tela do livro → Gerar audiobook, sem passar pela tela antiga de conversão).
     * O que foi escolhido no painel ([voz]/[motor]/[ritmo]) vale mais que o perfil do livro, que vale mais que os globais.
     */
    fun iniciarConversaoDoLivro(
        caminhoDoLivro: String,
        nomeAudiobook: String,
        voz: String? = null,
        motor: String? = null,
        ritmo: Int? = null,
        estilo: String = "padrao"
    ) = iniciarConversao(caminhoDoLivro, nomeAudiobook, caminhoDoLivro, true, estilo, voz, motor, ritmo)

    /**
     * Ponto único para iniciar uma conversão pela tela "Conversões": [arquivo] é o que será lido (o livro ou um trecho em texto),
     * [caminhoDoLivro] é o livro de onde vêm voz/velocidade/tom (e o vínculo do MP3 com o livro quando [livroInteiro]).
     */
    @Suppress("LongParameterList")
    fun iniciarConversao(
        arquivo: String,
        nomeAudiobook: String,
        caminhoDoLivro: String?,
        livroInteiro: Boolean,
        estilo: String = "padrao",
        voz: String? = null,
        motor: String? = null,
        ritmo: Int? = null,
        runToken: String? = null,
        speakerMapPath: String? = null,
        filaItemId: String? = null
    ) {
        val context = getApplication<Application>()
        val ajustes = ajustesDoLivro(caminhoDoLivro, voz, motor, ritmo)
        val appPrefs = com.jonjonesbr.audiobookgen.data.AppPrefs(context)
        val chave = com.jonjonesbr.audiobookgen.data.SecurePreferences.getGeminiKeys(context)
        val saidaInterna = java.io.File(context.filesDir, "$nomeAudiobook.mp3").absolutePath

        prepararConfiguracoesPython(ajustes.motor, chave, appPrefs.pausaMs)
        iniciarProcessamento(
            ParametrosConversao(
                arquivoEntrada = arquivo,
                voz = ajustes.voz,
                velocidade = com.jonjonesbr.audiobookgen.util.TtsRate.fromRitmo(ajustes.ritmo),
                estilo = estilo,
                caminhoSaidaInterna = saidaInterna,
                motor = ajustes.motor,
                runToken = runToken,
                livroOrigemCaminho = if (livroInteiro) caminhoDoLivro else null,
                speakerMapPath = speakerMapPath,
                tomMeiosTons = ajustes.tomMeiosTons,
                agudosDb = ajustes.agudosDb,
                filaItemId = filaItemId
            )
        )
    }

    /** Ajustes da conversão de um livro: escolha na hora > perfil do livro (voz, velocidade, tom) > preferências globais. */
    fun ajustesDoLivro(
        caminhoDoLivro: String?,
        escolhaVoz: String? = null,
        escolhaMotor: String? = null,
        escolhaRitmo: Int? = null
    ): com.jonjonesbr.audiobookgen.domain.AjustesDaConversao =
        com.jonjonesbr.audiobookgen.service.AjustesDoLivro.resolver(
            getApplication<Application>(), caminhoDoLivro, escolhaVoz, escolhaMotor, escolhaRitmo
        )

    fun iniciarProcessamento(params: ParametrosConversao) {
        // Cancela conversão em andamento: ExistingWorkPolicy.REPLACE abaixo, no enqueue.
        _uiState.update {
            it.copy(
                processando = true,
                progressoPct = 0,
                statusMessage = getApplication<Application>().getString(R.string.status_starting),
                conversionDone = null,
                progressoRunToken = params.runToken
            )
        }

        val inputData = params.toInputData(pausaMsAtual)

        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(ConversionWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        observarTrabalho(request.id)
    }

    /**
     * Passa a acompanhar a conversão que já está em andamento (ou na fila de espera), se houver: a fila avança sozinha no worker,
     * então a tela, ao abrir ou ao ver um item terminar, precisa "plugar" no próximo. Retorna depois de procurar.
     */
    fun acompanharTrabalhoAtual(runToken: String?) {
        viewModelScope.launch {
            val infos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    WorkManager.getInstance(getApplication()).getWorkInfosForUniqueWork(ConversionWorker.WORK_NAME).get()
                }.getOrDefault(emptyList())
            }
            val ativo = infos.firstOrNull { !it.state.isFinished } ?: return@launch
            _uiState.update {
                it.copy(
                    processando = true,
                    progressoPct = 0,
                    statusMessage = "",
                    conversionDone = null,
                    progressoRunToken = runToken
                )
            }
            observarTrabalho(ativo.id)
        }
    }

    private fun observarTrabalho(workId: java.util.UUID) {
        observacaoJob?.cancel()
        var handled = false
        observacaoJob = viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(workId)
                .filterNotNull()
                .collect { info ->
                    if (handled) return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val pct = info.progress.getInt(ConversionWorker.KEY_PROGRESS_PCT, 0)
                            val msg = info.progress.getString(ConversionWorker.KEY_PROGRESS_MSG) ?: ""
                            val token = info.progress.getString(ConversionWorker.KEY_RUN_TOKEN)
                            if (pct > 0 || msg.isNotBlank()) setProgresso(pct, msg, token)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            handled = true
                            setProcessando(false)
                            handleConversionSuccess(resultadoDeSucesso(info.outputData))
                        }
                        WorkInfo.State.FAILED -> {
                            handled = true
                            setProcessando(false)
                            val msg = info.outputData.getString(ConversionWorker.KEY_ERROR_MSG) ?: "Erro desconhecido"
                            val token = info.outputData.getString(ConversionWorker.KEY_RUN_TOKEN)
                                ?: _uiState.value.progressoRunToken
                            conversionNotif.cancelar()
                            _uiState.update {
                                it.copy(
                                    statusMessage  = getApplication<Application>().getString(R.string.status_conversion_error, msg),
                                    conversionDone = ConversionDoneEvent.Erro(msg, token)
                                )
                            }
                        }
                        WorkInfo.State.CANCELLED -> {
                            handled = true
                            setProcessando(false)
                            conversionNotif.cancelar()
                            val token = info.outputData.getString(ConversionWorker.KEY_RUN_TOKEN)
                                ?: _uiState.value.progressoRunToken
                            _uiState.update {
                                it.copy(
                                    statusMessage  = getApplication<Application>().getString(R.string.status_conversion_canceled),
                                    conversionDone = ConversionDoneEvent.Cancelada(token)
                                )
                            }
                        }
                        else -> { /* ENQUEUED / BLOCKED - aguarda */ }
                    }
                }
        }
    }

    /** Agrupa o outputData de sucesso pra não estourar o limite de parâmetros do handler. */
    private data class ResultadoConversao(
        val caminho: String,
        val duracao: String,
        val tamanhoMb: String,
        val capitulosJson: String? = null,
        val livroOrigemCaminho: String? = null,
        val runToken: String? = null,
        /** O worker já finalizou (marcas, vínculo e cópia para a pasta de saída); aqui só se lê o resultado. */
        val finalizado: Boolean = false,
        val exportUri: String? = null,
        val exportPasta: String? = null,
        val exportErro: String? = null
    )

    private fun resultadoDeSucesso(outputData: androidx.work.Data): ResultadoConversao {
        val w = ConversionWorker
        return ResultadoConversao(
            caminho = outputData.getString(w.KEY_RESULT_CAMINHO) ?: "",
            duracao = outputData.getString(w.KEY_RESULT_DURACAO) ?: "",
            tamanhoMb = outputData.getString(w.KEY_RESULT_TAMANHO) ?: "",
            capitulosJson = outputData.getString(w.KEY_RESULT_CAPITULOS),
            livroOrigemCaminho = outputData.getString(w.KEY_RESULT_LIVRO_ORIGEM),
            runToken = outputData.getString(w.KEY_RUN_TOKEN),
            finalizado = outputData.getBoolean(w.KEY_RESULT_FINALIZADO, false),
            exportUri = outputData.getString(w.KEY_RESULT_EXPORT_URI),
            exportPasta = outputData.getString(w.KEY_RESULT_EXPORT_PASTA),
            exportErro = outputData.getString(w.KEY_RESULT_EXPORT_ERRO)
        )
    }

    private suspend fun handleConversionSuccess(resultado: ResultadoConversao) {
        val status = if (resultado.finalizado) {
            // Feito pelo worker (independe de a tela continuar aberta).
            val uri = resultado.exportUri?.let { android.net.Uri.parse(it) }
            if (uri != null) {
                ExportStatus.Sucesso(com.jonjonesbr.audiobookgen.domain.ExportResult(uri, resultado.exportPasta.orEmpty()))
            } else {
                ExportStatus.Erro(resultado.exportErro ?: "Erro ao exportar")
            }
        } else {
            com.jonjonesbr.audiobookgen.service.ChapterMarksStore.salvarJson(
                getApplication(), resultado.caminho, resultado.capitulosJson
            )
            if (resultado.livroOrigemCaminho != null) {
                com.jonjonesbr.audiobookgen.data.LinkedBookStore.vincular(
                    getApplication(), resultado.livroOrigemCaminho, resultado.caminho
                )
            }
            exportUseCase.exportar(java.io.File(resultado.caminho), _uiState.value.pastaDestinoUri).fold(
                onSuccess = { ExportStatus.Sucesso(it) },
                onFailure = { ExportStatus.Erro(it.message ?: "Erro ao exportar") }
            )
        }
        // ConversionWorker já mostra a notificação de conclusão (com o caminho/nome corretos
        // pro botão de tocar) assim que doWork() termina — chamar de novo aqui, ao observar o
        // MESMO sucesso via Flow, era um repost duplicado e concorrente: se o usuário tocasse
        // na notificação bem nesse intervalo, ela podia "reaparecer" logo após ser dispensada.
        _uiState.update {
            it.copy(
                exportStatus   = status,
                statusMessage  = getApplication<Application>().getString(R.string.status_concluido),
                progressoPct   = 100,
                conversionDone = ConversionDoneEvent.Sucesso(
                    caminho    = resultado.caminho,
                    duracao    = resultado.duracao,
                    tamanhoMb  = resultado.tamanhoMb,
                    exportStatus = status,
                    runToken = resultado.runToken
                )
            )
        }
    }

    fun consumirEventoConversao() {
        _uiState.update { it.copy(conversionDone = null) }
    }

    fun setArquivoSelecionado(uri: Uri?, nome: String) {
        _uiState.update { it.copy(arquivoSelecionadoUri = uri, arquivoSelecionadoNome = nome) }
    }

    fun setPastaDestino(uri: Uri?) {
        _uiState.update { it.copy(pastaDestinoUri = uri) }
    }

    fun setUltimoAudio(uri: Uri?) {
        _uiState.update { it.copy(ultimoAudioUri = uri) }
    }

    fun setProcessando(ativo: Boolean) {
        _uiState.update { it.copy(processando = ativo) }
    }

    fun setProgresso(pct: Int, mensagem: String, runToken: String? = null) {
        _uiState.update { it.copy(progressoPct = pct, statusMessage = mensagem, progressoRunToken = runToken) }
    }

    fun pausarProcessamento() {
        // Cancela no WorkManager (marca como CANCELLED no banco de dados)
        WorkManager.getInstance(getApplication()).cancelUniqueWork(ConversionWorker.WORK_NAME)
        // Sinaliza o Python para parar o processamento atual
        pythonEngine.cancelar()
        setProcessando(false)
        conversionNotif.cancelar()
        _uiState.update { it.copy(statusMessage = getApplication<Application>().getString(R.string.status_conversion_canceled)) }
    }

    fun cancelarProcessamento() = pausarProcessamento()

    // ── Player Principal ──────────────────────────────────────────────────────

    fun iniciarPlayer(caminho: String, nome: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_INICIAR
            putExtra(AudioPlayerService.EXTRA_CAMINHO, caminho)
            putExtra(AudioPlayerService.EXTRA_NOME, nome)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun pararPlayer() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, AudioPlayerService::class.java).apply {
            action = PlayerNotificationManager.ACTION_FECHAR
        })
    }

    fun alternarPlayPause() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, AudioPlayerService::class.java).apply {
            action = PlayerNotificationManager.ACTION_PLAY_PAUSE
        })
    }

    // alternarPlayPause, seekPlayer, seekRelativo, aplicarVelocidade are called
    // directly in the Activity via AudioPlayerService binding - not needed in ViewModel.

    // ── Player de Amostra (Preview) ───────────────────────────────────────────

    /**
     * Fala EXATAMENTE [texto] com a voz/motor indicados (usado para ouvir uma pronúncia do dicionário;
     * a prévia comum de voz pode tocar um clipe fixo em vez do texto).
     */
    fun ouvirTexto(voz: String, ritmo: Int, motor: String, texto: String) {
        if (_uiState.value.amostraGerando || texto.isBlank()) return
        _uiState.update { it.copy(amostraGerando = true, statusMessage = getApplication<Application>().getString(R.string.status_gerando_amostra)) }
        viewModelScope.launch {
            val app = getApplication<android.app.Application>()
            val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)
            val caminho = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.tts.TtsSynthesizer.para(motorEfetivo, app, pythonEngine) {
                    com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine(app)
                }.synthesize(texto, voz, ritmo)
            }
            if (caminho != null) {
                _uiState.update { it.copy(amostraGerando = false, statusMessage = " Amostra Pronta") }
                iniciarPreview(caminho)
            } else {
                _uiState.update { it.copy(amostraGerando = false, statusMessage = getApplication<Application>().getString(R.string.status_amostra_falha)) }
            }
        }
    }

    fun iniciarPreview(caminho: String) = playerUseCase.iniciarPreview(caminho)

    fun pararPreview() = playerUseCase.pararPreview()

    override fun onCleared() {
        super.onCleared()
        observacaoJob?.cancel()
        playerUseCase.release()
    }
}
