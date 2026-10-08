package com.jonjonesbr.audiobookgen.ui

import android.net.Uri
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.ChapterExportPolicy
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionResult
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient
import com.jonjonesbr.audiobookgen.domain.SpeakerAiPlanner
import com.jonjonesbr.audiobookgen.domain.SpeakerAiRunner
import com.jonjonesbr.audiobookgen.domain.SpeakerAiStore
import com.jonjonesbr.audiobookgen.domain.SpeakerEdits
import com.jonjonesbr.audiobookgen.domain.SpeakerEditsStore
import com.jonjonesbr.audiobookgen.domain.SpeechLines
import com.jonjonesbr.audiobookgen.domain.SpeakerGender
import com.jonjonesbr.audiobookgen.domain.SpeakerGenderInferrer
import com.jonjonesbr.audiobookgen.domain.SpeakerVoiceSuggester
import com.jonjonesbr.audiobookgen.domain.DetectedSpeaker
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.MARGEM_NIVEL_PADRAO
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache
import com.jonjonesbr.audiobookgen.tts.AndroidVoiceId
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.aplicarMargemInferiorComNavigationBar
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import com.jonjonesbr.audiobookgen.util.dpToPx
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : BasePlayerActivity() {

    companion object {
        const val EXTRA_CAMINHO = "extra_caminho"
        /** True só quando o leitor foi aberto via [android.app.Activity.startActivityForResult]
         *  (MainActivity.abrirLeitor/abrirLeitorComCaminho/abrirLeituraGuiadaDirect) — nesses
         *  casos, RESULT_OK volta pro launcher que está esperando. Ausente/false quando o
         *  leitor foi aberto por notificação, mini-player ou "Continuar Lendo" (startActivity
         *  simples): aí não há ninguém ouvindo um ActivityResult, então as ações que
         *  devolveriam um resultado (Converter, Manter edições) reabrem a MainActivity com a
         *  mesma ação pendente em vez de perdê-la em silêncio. Ver [ReaderRetornoController]. */
        const val EXTRA_ABERTO_PARA_RESULTADO = "extra_aberto_para_resultado"
        const val EXTRA_TRECHO_PATH = "extra_trecho_path"
        private const val MS_POR_MINUTO = 60_000L
        const val EXTRA_TRECHO_NOME = "extra_trecho_nome"
        const val EXTRA_ACAO_RETORNO = "extra_acao_retorno"
        const val EXTRA_AUTO_START_GUIDED = "extra_auto_start_guided"
        private const val STATE_AUTO_START_RESOLVIDO = "state_auto_start_resolvido"
        private const val HINT_PERSONAGENS_MS = 10_000
        private const val EXCERTO_FALA_MAX = 90
        // Continua a leitura guiada direto na posição salva (sem diálogo de ponto de início).
        const val EXTRA_CONTINUAR_GUIADA = "extra_continuar_guiada"
        /** Painel a abrir assim que o texto carregar (vindo da tela do livro): uma das `ACAO_INICIAL_*`. */
        const val EXTRA_ACAO_INICIAL = "extra_acao_inicial"
        const val ACAO_INICIAL_CAPITULOS = "capitulos"
        const val ACAO_INICIAL_PERSONAGENS = "personagens"
        const val ACAO_INICIAL_PRONUNCIA = "pronuncia"
        /** Caminhos (ArrayList<String>) dos PRÓXIMOS arquivos a ler em sequência, em ordem,
         *  NÃO incluindo o arquivo atual — usado pelo botão "Ler tudo" da fila de conversão
         *  (QueueActivity). Quando a leitura guiada do arquivo atual termina naturalmente
         *  (ver GuidedState.livroConcluido), esta Activity abre o próximo caminho da lista
         *  com o restante como nova sequência e se fecha (ver avancarSequenciaFila()). */
        const val EXTRA_FILA_SEQUENCIA = "extra_fila_sequencia"
        const val ACAO_CONVERTER = "converter"
        const val ACAO_MANTER_EDICOES = "manter_edicoes"
        /** Ação de retorno do botão "Converter assim mesmo" (estado de erro do leitor): devolve o
         *  arquivo ORIGINAL pra MainActivity converter pelo pipeline tolerante (que não precisa de
         *  estrutura de capítulos) — o resultado pode sair sem divisão por capítulo. */
        const val ACAO_CONVERTER_SEM_ESTRUTURA = "converter_sem_estrutura"
        private const val DIALOG_PROGRESSO_PADDING_H = 24
        private const val DIALOG_PROGRESSO_PADDING_TOPO = 20
        private const val DIALOG_PROGRESSO_TEXT_SIZE_SP = 15f
        private const val DIALOG_PROGRESSO_TEXTO_BAIXO_DP = 16
        private const val PROGRESSO_MAX = 100
        private const val DURACAO_FADE_BANNER_MS = 300L
        private const val PARAGRAFOS_PREVIEW = 3
        private const val PREPARO_PARAGRAFOS_SEM_CAPITULO = 120
        private const val LARGURA_MAXIMA_LEITURA_DP = 720
        // Margem horizontal da lista de parágrafos por nível (0=estreita, 1=média, 2=larga).
        val MARGEM_NIVEL_DP = intArrayOf(8, 20, 36)
    }

    private val viewModel: ReaderViewModel by viewModels()
    private val audiobookViewModel: AudiobookViewModel by viewModels()
    private val speakerAttributionStore by lazy {
        SpeakerAttributionStore(File(filesDir, "speaker_attribution"))
    }
    private val speakerAiStore by lazy { SpeakerAiStore(File(filesDir, "speaker_attribution")) }
    private val speakerEditsStore by lazy { SpeakerEditsStore(File(filesDir, "speaker_attribution")) }
    private var speakerAiJob: Job? = null
    private var speakerAnalysisJob: Job? = null
    private var speakerAnalysisParagraphs: List<String>? = null
    private var speakerAttribution: SpeakerAttributionResult? = null
    /** Gênero PROVÁVEL por personagem (heurística sobre o nome); só para resumo e sugestão de vozes. */
    private var speakerGenders: Map<String, SpeakerGender> = emptyMap()
    private val speakerVoiceDownloadFlow by lazy { VoiceDownloadFlow(this) }

    // Views
    private lateinit var btnVoltar: ImageButton
    private lateinit var tvTitulo: TextView
    private lateinit var btnOpcoes: ImageButton
    private var dicaLeitorTentada = false
    private lateinit var scrollChips: HorizontalScrollView
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvProgressoLeitura: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutErro: LinearLayout
    private lateinit var tvErro: TextView
    private lateinit var btnConverterAssim: Button
    private lateinit var recyclerView: RecyclerView
    private var scrollListener: RecyclerView.OnScrollListener? = null
    // FAB único de conversão: texto/ícone mudam conforme há seleção ativa ou não
    // (antes eram dois FABs separados, fabConverterPrincipal e fabConverterSelecao,
    // sempre mutuamente exclusivos — unificados num só, ver atualizarFabs()).
    private lateinit var fabConverterPrincipal: ExtendedFloatingActionButton

    // Status pill (banner de limpeza)
    private lateinit var bannerOtimizado: android.view.ViewGroup
    private lateinit var tvBannerInfo: TextView

    // Barra de modo ativo
    private lateinit var layoutBarraModo: android.view.ViewGroup
    private lateinit var tvModoAtivo: TextView
    private lateinit var btnConcluirModo: Button
    private lateinit var btnBookmark: android.widget.ImageButton

    // T4.1 — Busca
    private lateinit var layoutBuscaReader: View
    private lateinit var etBuscaReader: android.widget.EditText
    private lateinit var tvBuscaContador: TextView
    private lateinit var btnBuscaAnterior: android.widget.ImageButton
    private lateinit var btnBuscaProximo: android.widget.ImageButton
    private lateinit var btnBuscaFechar: android.widget.ImageButton

    private lateinit var adapter: ParagrafoAdapter
    private var caminhoArquivo: String = ""
    private var arquivoTrechoTemp: File? = null
    private var preservarArquivoTrechoTemp = false
    private var dialogAtivo: androidx.appcompat.app.AlertDialog? = null
    // Marca de tempo de quando a tela ficou visível, para creditar minutos lidos nas
    // estatísticas ao sair (T4.5) — 0L quando a tela não está visível.
    private var telaVisivelDesdeMs = 0L
    private val capitulosSelecionados = linkedSetOf<Int>()
    private val exportarTextoLimpo = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val texto = viewModel.textoEfetivo()?.paragrafos
                ?.joinToString("\n\n") { it.texto } ?: return@registerForActivityResult
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Output stream unavailable")
            output.bufferedWriter().use { it.write(texto) }
            Toast.makeText(this, R.string.toast_clean_text_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("ReaderActivity", "Erro ao exportar texto limpo", e)
            Toast.makeText(this, R.string.toast_clean_text_export_error, Toast.LENGTH_SHORT).show()
        }
    }

    private val exportarCapitulosPasta = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) exportarCapitulosPara(uri) }

    private lateinit var guidedPlayerManager: GuidedPlayerManager
    private var ultimoTextoExibido: List<Paragrafo>? = null
    private var autoStartGuidedTriggered = false
    private var scrollRestaurado = false
    private var reconectandoGuided = false
    private var margemNivelAplicado = -1
    private var progressoIndiceAplicado = -1
    private var continuarGuiadaTriggered = false
    private val appPrefs by lazy { com.jonjonesbr.audiobookgen.data.AppPrefs(this) }
    private var ultimosCapitulosChips: List<com.jonjonesbr.audiobookgen.domain.Capitulo>? = null

    private val aparenciaController by lazy {
        ReaderAparenciaController(this, viewModel) { horizontal ->
            aplicarOrientacaoRolagem(horizontal)
        }
    }

    private val guidedReadingBarController by lazy {
        GuidedReadingBarController(
            activity = this,
            guidedPlayerManager = guidedPlayerManager,
            viewModel = viewModel,
            recyclerView = recyclerView,
            views = GuidedReadingBarController.Views(
                layoutGuidedPlayer = findViewById(R.id.layoutGuidedPlayer),
                tvGuidedStatus = findViewById(R.id.tvGuidedStatus),
                progressGuidedLoading = findViewById(R.id.progressGuidedLoading),
                btnGuidedVoz = findViewById(R.id.btnGuidedVoz),
                btnGuidedPrev = findViewById(R.id.btnGuidedPrev),
                btnGuidedPlayPause = findViewById(R.id.btnGuidedPlayPause),
                btnGuidedNext = findViewById(R.id.btnGuidedNext),
                btnGuidedClose = findViewById(R.id.btnGuidedClose),
                btnGuidedVelocidade = findViewById(R.id.btnGuidedVelocidade),
                tvGuidedSoneca = tvGuidedSoneca,
                tvGuidedPassos = findViewById(R.id.tvGuidedPassos),
            ),
            callbacks = GuidedReadingBarController.Callbacks(
                abrirSeletorVozes = { voiceSelectionDelegate.abrirSeletorVozes() },
                mostrarDialogoSleepTimer = { sonecaController.mostrarDialogoSleepTimer() },
                atualizarFabs = { atualizarFabs() },
                fecharPlayerService = { playerService?.fecharPlayer() },
                mostrarMiniPlayer = {
                    playerService?.state?.value?.let { ps ->
                        if (ps.isVisible) {
                            findViewById<View>(R.id.miniPlayerRoot)?.visibility = View.VISIBLE
                        }
                    }
                },
                esconderMiniPlayer = {
                    findViewById<View>(R.id.miniPlayerRoot)?.visibility = View.GONE
                },
                obterCaminhoArquivo = { caminhoArquivo },
                onLivroConcluido = { avancarSequenciaFila() },
            ),
        )
    }
    private val marcacoesController by lazy {
        ReaderMarcacoesController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderMarcacoesController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterParagrafoAtual = { paragrafoAtualVisivel() },
                rolarParaParagrafo = { pos -> recyclerView.smoothScrollToPosition(pos) },
                atualizarIconeBookmarkView = { temBookmark, descricao ->
                    btnBookmark.setImageResource(
                        if (temBookmark) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_border
                    )
                    btnBookmark.contentDescription = descricao
                },
                atualizarDestaquesAdapter = { destaques ->
                    adapter.destaques = destaques
                    adapter.notifyDataSetChanged()
                },
            ),
        )
    }

    private val sonecaController by lazy {
        ReaderSonecaController(
            activity = this,
            callbacks = ReaderSonecaController.Callbacks(
                obterTokenSessaoGuided = {
                    if (::guidedPlayerManager.isInitialized) guidedPlayerManager.sessionToken else null
                },
                pausarGuided = { guidedPlayerManager.pause() },
                pausarPlayerSeTocando = {
                    if (playerService?.state?.value?.isPlaying == true) {
                        playerService?.alternarPlayPause()
                    }
                },
                onAtualizarSoneca = { ms ->
                    tvGuidedSoneca.text =
                        if (ms != null) "😴 ${SleepTimerManager.formatRemaining(ms)}" else "😴"
                    tvGuidedSoneca.visibility = View.VISIBLE
                },
            ),
        )
    }

    private val retornoController by lazy {
        ReaderRetornoController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderRetornoController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterCapitulosSelecionados = { capitulosSelecionados },
                consumirEdicoesAdapter = { adapter.consumirEdicoes() },
                onRegistrarTrechoTemp = { arquivo ->
                    arquivoTrechoTemp = arquivo
                    preservarArquivoTrechoTemp = true
                },
                onExportarTextoLimpo = {
                    exportarTextoLimpo.launch("texto_limpo_${System.currentTimeMillis()}.txt")
                },
                onExportarCapitulosPasta = { exportarCapitulosPasta.launch(null) },
                onCompartilharTrecho = { compartilharTrechoAtual() },
                onBaixarCapitulo = { baixarCapitulo() },
                onBaixarLivro = { baixarLivroCompleto() },
                onOuvirAudiobook = { ouvirAudiobookDesteLivro() },
                onAbrirCapitulos = { mostrarBottomSheetCapitulos() },
                onConfigurarVozesPersonagens = { abrirConfiguracaoVozesPersonagens() },
                onAbrirMarcadores = { marcacoesController.mostrarDialogListarBookmarks() },
                onAbrirDestaques = { marcacoesController.mostrarDialogListarDestaques() },
                onIniciarLeituraGuiada = {
                    guidedReadingBarController.playGuidedFrom(guidedReadingBarController.obterIndiceInicioLeitura())
                },
                onPrepararCapitulo = { prepararCapituloAtual() },
                onAbrirAparencia = { aparenciaController.mostrarBottomSheet() },
                onAbrirSleepTimer = { sonecaController.mostrarDialogoSleepTimer() },
                onClonarVoz = { cloneVoiceFlow.iniciar() },
            ),
        )
    }
    
    // Sleep Timer: a contagem vive em SleepTimerManager (sobrevive à Activity e aparece na
    // notificação). A Activity só dispara/observa.

    private lateinit var tvGuidedSoneca: TextView

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Só marca como resolvido se o diálogo já teve escolha; aberto na hora da rotação, reaparece.
        outState.putBoolean(STATE_AUTO_START_RESOLVIDO,
            autoStartGuidedTriggered && !guidedReadingBarController.dialogoInicioPendente)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStartGuidedTriggered = savedInstanceState?.getBoolean(STATE_AUTO_START_RESOLVIDO) ?: false
        setContentView(R.layout.activity_reader)
        configurarMiniPlayerBotoes()

        caminhoArquivo = intent.getStringExtra(EXTRA_CAMINHO) ?: run { finish(); return }
        // Livro da biblioteca: registra a abertura (ordena "recentes" e alimenta o cartão Continuar).
        lifecycleScope.launch {
            com.jonjonesbr.audiobookgen.data.BibliotecaStore.marcarAberto(applicationContext, caminhoArquivo)
        }
        // Livro da biblioteca: usa o título dele (com acentos), não o nome do arquivo (Dom_Casmurro).
        val tituloCompleto = com.jonjonesbr.audiobookgen.data.BibliotecaStore.tituloPorCaminho(this, caminhoArquivo)
            ?: File(caminhoArquivo).nameWithoutExtension

        // Precisa rodar ANTES de vincularViews()/guidedReadingBarController abaixo: o
        // lazy de guidedReadingBarController lê guidedPlayerManager (lateinit) no
        // primeiro acesso (configurarGuidedPlayerBotoes()); se guidedPlayerManager
        // ainda não tiver sido atribuído aqui, crasha com
        // UninitializedPropertyAccessException (bug real, já reproduzido no aparelho).
        val pythonUseCase = PythonEngineUseCase(this)
        // Reconecta ao manager em execução se já houver leitura guiada ativa para ESTE documento
        // (ex.: usuário voltou ao menu e reabriu pelo indicador/notificação). Senão, cria um novo.
        // Usa applicationContext para o manager poder sobreviver ao fim desta Activity.
        val managerExistente = GuidedPlaybackBridge.manager
        guidedPlayerManager = if (managerExistente != null &&
            GuidedPlaybackBridge.caminhoArquivo == caminhoArquivo &&
            managerExistente.state.value.index >= 0) {
            reconectandoGuided = true
            managerExistente
        } else {
            managerExistente?.release()
            GuidedPlayerManager(applicationContext, pythonUseCase)
        }
        guidedPlayerManager.trackName = tituloCompleto
        guidedPlayerManager.caminhoArquivo = caminhoArquivo
        // Reconectando a uma sessão já ativa: os campos acima foram atualizados diretamente no
        // manager (sem passar por play()), então republica a mesma escrita atômica no bridge
        // para não deixar trackName/caminho desalinhados com o token/manager já publicados.
        if (GuidedPlaybackBridge.manager === guidedPlayerManager) {
            GuidedPlaybackBridge.activate(guidedPlayerManager, tituloCompleto, caminhoArquivo)
        }

        vincularViews()
        configurarRecyclerView()
        configurarBotoes()
        guidedReadingBarController.configurarGuidedPlayerBotoes()
        configurarScrollListener()
        onBackPressedDispatcher.addCallback(this) { retornoController.retornarMantendoEdicoes() }

        tvTitulo.text = tituloCompleto
        tvTitulo.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(null)
                .setMessage(tituloCompleto)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
            true
        }

        val prefs = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)
        viewModel.carregarPreferencias(prefs)
        // configurarRecyclerView() já rodou com o default (vertical) — carregarPreferencias()
        // só agora carrega o valor salvo, então reaplica a orientação correta.
        aplicarOrientacaoRolagem(viewModel.uiState.value.rolagemHorizontal)

        // Bug #4 fix: sincroniza motor TTS, chave e pausas para o GuidedPlayerManager
        val motorInit = appPrefs.motorTts
        val chaveInit = SecurePreferences.getGeminiKeys(this)
        val pausaMsInit = appPrefs.pausaMs
        lifecycleScope.launch {
            pythonUseCase.sincronizarConfiguracoes(motorInit, chaveInit, pausaMsInit)
        }

        observarViewModel()
        guidedReadingBarController.observarGuidedPlayer()
        sonecaController.observarSleepTimer()
        observarAudiobookViewModel()

        // NÃO usar `savedInstanceState == null` aqui: após morte de processo o Android
        // recria a Activity com savedInstanceState preenchido (restaurado do disco), mas o
        // ViewModel é uma instância NOVA (sem SavedStateHandle) — o texto do livro estaria
        // vazio para sempre, sem erro nem loading, uma tela em branco. O que importa é se O
        // VIEWMODEL já tem o texto carregado (sobrevive à rotação, não à morte de processo).
        if (viewModel.uiState.value.texto == null) {
            viewModel.carregarArquivo(caminhoArquivo)
        }
    }

    override fun onStart() {
        super.onStart()
        telaVisivelDesdeMs = System.currentTimeMillis()
    }

    override fun onStop() {
        super.onStop()
        registrarMinutosLidos()
        if (::adapter.isInitialized) retornoController.aplicarEdicoesPendentes()
        dialogAtivo?.dismiss()
        dialogAtivo = null
        val prefs = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)
        viewModel.salvarPreferencias(prefs)
        // Salva a posição de leitura (primeiro parágrafo visível) para retomar depois.
        if (::recyclerView.isInitialized && caminhoArquivo.isNotEmpty()) {
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?.takeIf { it >= 0 }
                ?.let {
                    prefs.edit().putInt("reader_scroll_$caminhoArquivo", it)
                        // Total de parágrafos: dá a porcentagem lida (progresso = scroll / total) na biblioteca.
                        .putInt("reader_total_$caminhoArquivo", if (::adapter.isInitialized) adapter.itemCount else 0).apply()
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scrollListener?.let { recyclerView.removeOnScrollListener(it) }
        scrollListener = null
        if (::guidedPlayerManager.isInitialized) {
            // Mantém a leitura guiada tocando em 2º plano (foreground service); só libera o
            // manager quando a leitura está parada. Reabrir o leitor reconecta ao manager vivo.
            if (guidedPlayerManager.state.value.index < 0) {
                if (guidedPlayerManager.preparandoCapitulo()) {
                    // O preparo do capítulo segue em segundo plano (com notificação); o manager se
                    // libera sozinho quando ele terminar.
                    guidedPlayerManager.liberarAoFimDoPreparo = true
                } else {
                    guidedPlayerManager.release()
                }
            }
        }
        if (!preservarArquivoTrechoTemp) {
            arquivoTrechoTemp?.delete()
        }
        // NÃO cancela o sleep timer aqui: ele deve continuar mesmo com a tela bloqueada/app
        // em 2º plano (a contagem vive em SleepTimerManager e os serviços pausam ao terminar).
    }

    // O leitor tem sua própria barra de leitura guiada (layoutGuidedPlayer) — não exibe o
    // indicador in-app do BasePlayerActivity para evitar duplicidade.
    override fun mostrarIndicadorGuided(): Boolean = false

    // Simplificação deliberada: conta o tempo com a tela do leitor visível como "minutos
    // lidos", mesmo quando a leitura guiada (TTS) está tocando ao mesmo tempo — nesse caso o
    // mesmo intervalo também soma em "minutos ouvidos" (GuidedPlayerManager). É uma medida
    // aproximada de engajamento, não uma partição exata do tempo do usuário.
    private fun registrarMinutosLidos() {
        val desde = telaVisivelDesdeMs
        telaVisivelDesdeMs = 0L
        if (desde <= 0L) return
        val minutos = ((System.currentTimeMillis() - desde) / MS_POR_MINUTO).toInt()
        if (minutos > 0) {
            com.jonjonesbr.audiobookgen.data.StatsStore.registrarMinutosLidos(this, minutos)
        }
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private fun vincularViews() {
        btnVoltar            = findViewById(R.id.btnReaderVoltar)
        tvTitulo             = findViewById(R.id.tvReaderTitulo)
        btnOpcoes            = findViewById(R.id.btnReaderOpcoes)
        scrollChips          = findViewById(R.id.scrollChips)
        chipGroup            = findViewById(R.id.chipGroupCapitulos)
        tvProgressoLeitura   = findViewById(R.id.tvProgressoLeitura)
        progressBar          = findViewById(R.id.readerProgressBar)
        layoutErro           = findViewById(R.id.layoutReaderErro)
        tvErro               = findViewById(R.id.tvReaderErro)
        btnConverterAssim    = findViewById(R.id.btnConverterAssim)
        recyclerView         = findViewById(R.id.recyclerViewParagrafos)
        recyclerView.limitarLarguraEmTelaAmpla(LARGURA_MAXIMA_LEITURA_DP)
        fabConverterPrincipal = findViewById(R.id.fabConverterPrincipal)
        // T6.1 (edge-to-edge): FAB e barra de leitura guiada são ancorados na base da tela —
        // sem isso, ficam atrás da barra de gestos em telas sem botões de navegação físicos.
        fabConverterPrincipal.aplicarMargemInferiorComNavigationBar()
        findViewById<View>(R.id.layoutGuidedPlayer).aplicarPaddingInferiorComNavigationBar()
        bannerOtimizado      = findViewById(R.id.bannerTextoOtimizado)
        tvBannerInfo         = findViewById(R.id.tvBannerInfo)
        layoutBarraModo      = findViewById(R.id.layoutBarraModo)
        tvModoAtivo          = findViewById(R.id.tvModoAtivo)
        btnConcluirModo      = findViewById(R.id.btnConcluirModo)

        tvGuidedSoneca        = findViewById(R.id.tvGuidedSoneca)

        btnBookmark          = findViewById(R.id.btnBookmarkReader)

        layoutBuscaReader    = findViewById(R.id.layoutBuscaReader)
        etBuscaReader        = findViewById(R.id.etBuscaReader)
        tvBuscaContador      = findViewById(R.id.tvBuscaContador)
        btnBuscaAnterior     = findViewById(R.id.btnBuscaAnterior)
        btnBuscaProximo      = findViewById(R.id.btnBuscaProximo)
        btnBuscaFechar       = findViewById(R.id.btnBuscaFechar)
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun configurarRecyclerView() {
        adapter = ParagrafoAdapter(
            activity = this,
            recyclerView = recyclerView,
            viewModel = viewModel,
            onLongPress = { indice -> viewModel.onParagrafoLongPress(indice) },
            onToque     = { indice -> guidedReadingBarController.onParagrafoToque(indice) },
            onDestacarTrecho = { indice, inicio, fim ->
                marcacoesController.mostrarDialogDestacarTrecho(indice, inicio, fim)
            },
            onDefinirPronuncia = { palavra ->
                PronunciationDialog.show(this, palavra, onOuvir = ::ouvirPronuncia)
            },
        )
        aplicarOrientacaoRolagem(viewModel.uiState.value.rolagemHorizontal)
        recyclerView.adapter = adapter
        viewModel.registrarAdapter(adapter)
    }

    private var pagerSnapHelper: androidx.recyclerview.widget.PagerSnapHelper? = null

    /** Alterna o RecyclerView entre scroll vertical contínuo (padrão) e horizontal paginado
     * (com PagerSnapHelper — "página" por página é o mesmo parágrafo, unidade de navegação
     * inalterada). Chamada tanto na criação quanto ao alternar a opção ao vivo. */
    private fun aplicarOrientacaoRolagem(horizontal: Boolean) {
        pagerSnapHelper?.attachToRecyclerView(null)
        recyclerView.layoutManager = LinearLayoutManager(
            this,
            if (horizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL,
            false
        )
        if (horizontal) {
            pagerSnapHelper = androidx.recyclerview.widget.PagerSnapHelper().apply {
                attachToRecyclerView(recyclerView)
            }
        } else {
            pagerSnapHelper = null
        }
    }

    // ── Botões ────────────────────────────────────────────────────────────────

    private fun configurarBotoes() {
        btnVoltar.setOnClickListener { retornoController.retornarMantendoEdicoes() }

        findViewById<android.widget.ImageButton>(R.id.btnBuscarReader)
            .setOnClickListener { alternarBusca() }

        btnBookmark.setOnClickListener { marcacoesController.alternarBookmarkNaPosicaoAtual() }

        // Botão de mais opções: abre o BottomSheet unificado de ações
        btnOpcoes.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // Pill de status: também abre o BottomSheet de ações
        bannerOtimizado.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // FAB de conversão: converter livro inteiro OU seleção, dependendo do estado atual
        fabConverterPrincipal.setOnClickListener {
            verificarConectividadeAntesDeConverter { retornoController.retornarComSelecao() }
        }

        // Barra de modo: botão Concluir
        btnConcluirModo.setOnClickListener {
            if (viewModel.uiState.value.modoEdicao) {
                retornoController.aplicarEdicoesPendentes()
                viewModel.desativarModoEdicao()
            } else {
                viewModel.toggleMostrarAlteracoes()
            }
        }

        // Estado de erro (extração falhou): "Converter assim mesmo" devolve o arquivo ORIGINAL
        // pra MainActivity converter pelo pipeline tolerante — o leitor não tem texto estruturado
        // pra devolver, então só fecha depois de checar conectividade (como os demais caminhos).
        btnConverterAssim.setOnClickListener {
            verificarConectividadeAntesDeConverter {
                retornoController.retornarConvertendoOriginalSemEstrutura()
            }
        }

        // ── Busca ──────────────────────────────────────────────────────
        etBuscaReader.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.buscarTexto(s?.toString() ?: "")
            }
        })

        etBuscaReader.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                viewModel.navegarBusca(1)
                scrollParaBuscaAtual()
                true
            } else false
        }

        btnBuscaAnterior.setOnClickListener {
            viewModel.navegarBusca(-1)
            scrollParaBuscaAtual()
        }

        btnBuscaProximo.setOnClickListener {
            viewModel.navegarBusca(1)
            scrollParaBuscaAtual()
        }

        btnBuscaFechar.setOnClickListener { alternarBusca() }
    }

    private fun alternarBusca() {
        viewModel.toggleBusca()
        layoutBuscaReader.visibility = if (viewModel.uiState.value.buscaVisivel) View.VISIBLE else View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
            android.view.inputmethod.InputMethodManager
        if (viewModel.uiState.value.buscaVisivel) {
            etBuscaReader.requestFocus()
            imm.showSoftInput(etBuscaReader, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            etBuscaReader.setText("")
            imm.hideSoftInputFromWindow(etBuscaReader.windowToken, 0)
        }
    }

    private fun scrollParaBuscaAtual() {
        val posicao = posicaoResultadoBusca(adapter.buscaResultados, adapter.buscaIndiceAtual)
        if (posicao != null) {
            recyclerView.smoothScrollToPosition(posicao)
        }
    }

    private fun configurarScrollListener() {
        scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    guidedReadingBarController.scrollManual = true
                    viewModel.pausarSync()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    guidedReadingBarController.aguardandoAutoScroll = false
                    atualizarProgressoLeitura()
                }
            }
        }
        recyclerView.addOnScrollListener(scrollListener!!)
    }

    /**
     * Visibilidade e conteúdo do FAB de conversão (único — ver [fabConverterPrincipal]).
     * Escondido durante leitura guiada (pra não ficar clicável atrás da barra de leitura
     * guiada, toques acidentais ao usar voz/velocidade); em modos especiais (edição/diff)
     * some também, A MENOS que já haja seleção ativa (mesmo comportamento de antes da
     * unificação dos dois FABs). Com seleção ativa, troca ícone/texto para refletir isso
     * em vez de mostrar um segundo FAB no mesmo lugar.
     */
    private fun atualizarFabs() {
        if (!::guidedPlayerManager.isInitialized) return
        val state = viewModel.uiState.value
        val temCapitulos = capitulosSelecionados.isNotEmpty()
        val temSelecaoAtiva = state.selecaoInicio != null || temCapitulos
        val emModoEspecial = state.modoEdicao || state.mostrandoAlteracoes
        val guiadaAtiva = guidedPlayerManager.state.value.index >= 0

        val fabVisivel = if (temSelecaoAtiva) !guiadaAtiva else !emModoEspecial && !guiadaAtiva
        fabConverterPrincipal.visibility = if (fabVisivel) View.VISIBLE else View.GONE

        if (temSelecaoAtiva) {
            fabConverterPrincipal.setIconResource(android.R.drawable.checkbox_on_background)
            fabConverterPrincipal.text = if (temCapitulos) {
                resources.getQuantityString(
                    R.plurals.fab_converter_capitulos,
                    capitulosSelecionados.size,
                    capitulosSelecionados.size
                )
            } else {
                getString(R.string.fab_converter_selecao)
            }
        } else {
            fabConverterPrincipal.setIconResource(android.R.drawable.ic_btn_speak_now)
            fabConverterPrincipal.text = getString(R.string.fab_converter_principal)
        }
    }

    // ── Observar ViewModel ────────────────────────────────────────────────────

    private fun observarViewModel() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    atualizarUI(state)
                }
            }
        }
    }

    private var dialogProgressoConversao: androidx.appcompat.app.AlertDialog? = null
    private var tvDialogProgressoStatus: TextView? = null
    private var barDialogProgresso: ProgressBar? = null

    private fun mostrarOuAtualizarDialogProgress(progresso: Int, mensagem: String) {
        if (isFinishing) return
        if (dialogProgressoConversao == null) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dpToPx(DIALOG_PROGRESSO_PADDING_H),
                    dpToPx(DIALOG_PROGRESSO_PADDING_TOPO),
                    dpToPx(DIALOG_PROGRESSO_PADDING_H),
                    dpToPx(DIALOG_PROGRESSO_PADDING_H)
                )
            }
            tvDialogProgressoStatus = TextView(this).apply {
                text = "Iniciando..."
                textSize = DIALOG_PROGRESSO_TEXT_SIZE_SP
                setTextColor(Color.parseColor("#424242"))
                setPadding(0, 0, 0, dpToPx(DIALOG_PROGRESSO_TEXTO_BAIXO_DP))
            }
            barDialogProgresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = PROGRESSO_MAX
                progress = 0
            }
            container.addView(tvDialogProgressoStatus)
            container.addView(barDialogProgresso)
            
            dialogProgressoConversao = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.toast_download_started))
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    audiobookViewModel.cancelarProcessamento()
                }
                .create()
            dialogProgressoConversao?.show()
        }
        
        tvDialogProgressoStatus?.text = mensagem
        barDialogProgresso?.progress = progresso
    }

    private fun esconderDialogProgress() {
        dialogProgressoConversao?.dismiss()
        dialogProgressoConversao = null
        tvDialogProgressoStatus = null
        barDialogProgresso = null
    }

    private fun observarAudiobookViewModel() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                audiobookViewModel.uiState.collect { state ->
                    if (state.processando) {
                        mostrarOuAtualizarDialogProgress(state.progressoPct, state.statusMessage)
                    } else {
                        esconderDialogProgress()
                    }

                    state.conversionDone?.let { evento ->
                        audiobookViewModel.consumirEventoConversao()
                        when (evento) {
                            is ConversionDoneEvent.Sucesso -> {
                                Toast.makeText(
                                    this@ReaderActivity,
                                    R.string.toast_download_completed,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ConversionDoneEvent.Cancelada -> {
                                Toast.makeText(
                                    this@ReaderActivity,
                                    R.string.status_conversion_canceled,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is ConversionDoneEvent.Erro -> {
                                val msg = getString(R.string.toast_download_failed, evento.mensagem)
                                Toast.makeText(this@ReaderActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun baixarCapitulo() {
        val texto = viewModel.textoEfetivo() ?: return
        val capAtivo = viewModel.uiState.value.capituloAtivo
        val cap = texto.capitulos.getOrNull(capAtivo) ?: return
        
        val trechoTexto = texto.paragrafos.subList(
            cap.indiceParagrafoInicio.coerceAtLeast(0),
            (cap.indiceParagrafoFim + 1).coerceAtMost(texto.paragrafos.size)
        ).joinToString("\n\n") { it.texto }
        
        val nomeSemExt = File(caminhoArquivo).nameWithoutExtension
        val nomeCapitulo = "${nomeSemExt} - ${cap.titulo}"
        
        // Vozes dos personagens também no capítulo avulso (o mapa serve a qualquer trecho contínuo do livro).
        val textosDoLivro = texto.paragrafos.map { it.texto }
        val speakerMapPath = speakerAttribution
            ?.takeIf { result -> result.speakers.any { !it.voiceId.isNullOrBlank() } }
            ?.takeIf { speakerAnalysisParagraphs == textosDoLivro }
            ?.let { speakerAttributionStore.fileForBook(caminhoArquivo) }
            ?.takeIf { it.isFile }
            ?.absolutePath
        val arquivoTemp = File(cacheDir, "download_chapter_${System.currentTimeMillis()}.txt")
        try {
            arquivoTemp.writeText(trechoTexto, Charsets.UTF_8)
            iniciarDownloadBackground(arquivoTemp.absolutePath, nomeCapitulo, speakerMapPath = speakerMapPath, caminhoDoLivro = caminhoArquivo)
        } catch (e: Exception) {
            Log.w("ReaderActivity", "Erro ao preparar download de capítulo", e)
            Toast.makeText(
                this,
                getString(R.string.toast_erro_preparar_download, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun baixarLivroCompleto() {
        val texto = viewModel.textoEfetivo() ?: return
        val nomeSemExt = File(caminhoArquivo).nameWithoutExtension
        lifecycleScope.launch {
            speakerAnalysisJob?.join()
            val trechoTexto = texto.paragrafos.joinToString("\n\n") { it.texto }
            val textosDoLivro = texto.paragrafos.map { it.texto }
            val speakerMapPath = speakerAttribution
                ?.takeIf { result -> result.speakers.any { !it.voiceId.isNullOrBlank() } }
                ?.takeIf { speakerAnalysisParagraphs == textosDoLivro }
                ?.let { speakerAttributionStore.fileForBook(caminhoArquivo) }
                ?.takeIf { it.isFile }
                ?.absolutePath
            val arquivoTemp = File(cacheDir, "download_book_${System.currentTimeMillis()}.txt")
            try {
                arquivoTemp.writeText(trechoTexto, Charsets.UTF_8)
                iniciarDownloadBackground(
                    arquivoTemp.absolutePath,
                    nomeSemExt,
                    livroOrigemCaminho = caminhoArquivo,
                    speakerMapPath = speakerMapPath
                )
            } catch (e: Exception) {
                Log.w("ReaderActivity", "Erro ao preparar download de livro completo", e)
                Toast.makeText(
                    this@ReaderActivity,
                    getString(R.string.toast_erro_preparar_download, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportarCapitulosPara(pastaUri: Uri) {
        val texto = viewModel.textoEfetivo()
        if (texto == null || texto.capitulos.isEmpty()) {
            Toast.makeText(this, R.string.toast_sem_capitulos_para_exportar, Toast.LENGTH_SHORT).show()
            return
        }
        val nomeLivro = File(caminhoArquivo).nameWithoutExtension
        val arquivos = ChapterExportPolicy.montarArquivosCapitulos(texto.capitulos, texto.paragrafos)
        lifecycleScope.launch {
            val resultado = DocumentExportUseCase(this@ReaderActivity)
                .exportarTextos("${nomeLivro}_capitulos", arquivos, pastaUri)
            val mensagem = if (resultado.isSuccess) {
                getString(R.string.toast_capitulos_exportados, resultado.getOrDefault(0))
            } else {
                getString(
                    R.string.toast_erro_exportar_capitulos,
                    resultado.exceptionOrNull()?.message ?: ""
                )
            }
            Toast.makeText(this@ReaderActivity, mensagem, Toast.LENGTH_LONG).show()
        }
    }


    private fun iniciarDownloadBackground(
        caminhoTexto: String,
        nomeAudiobook: String,
        livroOrigemCaminho: String? = null,
        speakerMapPath: String? = null,
        caminhoDoLivro: String? = livroOrigemCaminho
    ) {
        audiobookViewModel.iniciarProcessamentoDeTexto(
            caminhoTexto,
            nomeAudiobook,
            livroOrigemCaminho,
            speakerMapPath,
            caminhoDoLivro
        )
        // Avisa que começou e leva ao andamento (antes a conversão iniciada aqui não tinha onde ser acompanhada).
        com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.conv_iniciada_do_leitor),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction(R.string.conv_ver) {
            startActivity(Intent(this, ConversoesActivity::class.java))
        }.show()
    }

    /**
     * Abre o audiobook já convertido deste livro (vínculo criado em [baixarLivroCompleto],
     * via [com.jonjonesbr.audiobookgen.data.LinkedBookStore]) — ponte de UI entre leitura e
     * escuta (Fase 5, item 2), sem sincronizar posição automaticamente.
     */
    private fun ouvirAudiobookDesteLivro() {
        val mp3 = com.jonjonesbr.audiobookgen.data.LinkedBookStore.mp3Vinculado(this, caminhoArquivo)
            ?: return
        if (!File(mp3).exists()) return
        val nome = File(mp3).nameWithoutExtension
        playerService?.iniciar(mp3, nome) ?: run {
            val intent = Intent(this, com.jonjonesbr.audiobookgen.service.AudioPlayerService::class.java).apply {
                action = com.jonjonesbr.audiobookgen.service.AudioPlayerService.ACTION_INICIAR
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_CAMINHO, mp3)
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_NOME, nome)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun atualizarUI(state: ReaderUiState) {
        // Loading
        progressBar.visibility = if (state.carregando) View.VISIBLE else View.GONE

        // Erro
        if (state.erro != null) {
            layoutErro.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            scrollChips.visibility = View.GONE
            tvErro.text = state.erro
            return
        }

        layoutErro.visibility = View.GONE

        val texto = viewModel.textoEfetivo()
        if (texto == null) return

        processarAutoStartLeituraGuiada(texto)

        // Mostra conteúdo
        recyclerView.visibility = View.VISIBLE

        atualizarChipsCapitulos(texto, state.capituloAtivo)

        // ── Pill de status (banner de limpeza) ────────────────────────────────
        atualizarPillStatusLeitura(state, texto)

        // ── Barra de modo ativo ────────────────────────────────────────────────
        atualizarBarraModoAtivo(state)

        // ── Modo do adapter ────────────────────────────────────────────────────
        val paragrafosExibidos = atualizarAdapterDeLeitura(state, texto)

        // ── Busca ──────────────────────────────────────────────────────────────
        atualizarContadorBusca(state)

        // Restaura a posição de leitura salva — apenas uma vez, no carregamento inicial,
        // e nunca quando a leitura guiada vai iniciar sozinha (ela faz seu próprio scroll).
        restaurarPosicaoLeituraSeNecessario(paragrafosExibidos)
        executarAcaoInicialSeNecessario()
        if (!dicaLeitorTentada) {
            dicaLeitorTentada = true
            btnOpcoes.post { CoachMark.tentar(this, com.jonjonesbr.audiobookgen.domain.Dica.LEITOR_GUIADA, btnOpcoes) }
        }

        // ── FABs ──────────────────────────────────────────────────────────────
        atualizarFabs()

        // Scroll sync automático (só se não foi scroll manual). Só rola quando o
        // parágrafo muda — atualizarUI roda a cada tick de progresso (20fps) e
        // re-rolar para o mesmo índice causava flicker (#3).
        sincronizarScrollAutomatico(state)

        // Aplica tema ao fundo
        aplicarTemaFundo(state)

        // Margem horizontal da lista de parágrafos (0=estreita, 1=média, 2=larga).
        aplicarMargemParagrafos(state)

        atualizarProgressoLeitura()
    }

    /** Extraído de [atualizarUI] (ações disparadas pelas intents de leitura guiada). */
    private fun processarAutoStartLeituraGuiada(texto: TextoExtraido) {
        // Não reabrir o diálogo ao reconectar a uma leitura em andamento (ex.: rotação da tela):
        // a intent original ainda carrega EXTRA_AUTO_START_GUIDED e o flag da instância se perde.
        if (intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false) &&
            !autoStartGuidedTriggered && !reconectandoGuided) {
            autoStartGuidedTriggered = true
            guidedReadingBarController.mostrarDialogPontoInicioLeituraGuiada()
        }

        // "Continuar lendo": inicia a guiada direto na posição salva (sem diálogo). Pulado se
        // estiver reconectando a uma leitura já em andamento (que já está tocando).
        if (intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false) &&
            !continuarGuiadaTriggered && !reconectandoGuided) {
            continuarGuiadaTriggered = true
            val total = texto.paragrafos.size
            val salvo = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)
                .getInt("reader_scroll_$caminhoArquivo", -1)
            val inicio = if (salvo in 0 until total) salvo else guidedReadingBarController.obterIndiceInicioLeitura()
            recyclerView.post { guidedReadingBarController.playGuidedFrom(inicio) }
        }
    }

    /** Ler tudo da fila (QueueActivity): quando o livro atual termina naturalmente e a
     *  intent trouxe uma sequência de caminhos pendentes, abre o próximo e encerra esta
     *  Activity — repetindo até a sequência esvaziar. Sem sequência (leitura avulsa
     *  normal), não faz nada. Usa EXTRA_CONTINUAR_GUIADA (não EXTRA_AUTO_START_GUIDED) pra
     *  começar direto, sem o diálogo de "de onde começar" interromper a sequência a cada
     *  arquivo novo. */
    private fun avancarSequenciaFila() {
        val restante = intent.getStringArrayListExtra(EXTRA_FILA_SEQUENCIA)
        if (restante.isNullOrEmpty()) return
        val proximo = restante.removeAt(0)
        val proximaIntent = Intent(this, ReaderActivity::class.java)
            .putExtra(EXTRA_CAMINHO, proximo)
            .putExtra(EXTRA_CONTINUAR_GUIADA, true)
            .putStringArrayListExtra(EXTRA_FILA_SEQUENCIA, restante)
        // BUG REAL (relatado pelo usuário): ReaderActivity é `launchMode="singleTop"` — como
        // esta Activity É a instância no topo da pilha, startActivity() ANTES de finish()
        // fazia o Android tratar como "reentrega de intent" (onNewIntent, não implementado
        // aqui) em vez de abrir o próximo livro; a sequência simplesmente parava no 1º
        // arquivo. finish() PRIMEIRO marca esta instância como finalizando antes do
        // startActivity() seguinte resolver — o sistema já não a considera "topo da pilha"
        // e cria uma instância nova de verdade (com onCreate rodando do zero pro novo
        // caminho), como esperado.
        finish()
        startActivity(proximaIntent)
    }

    /** Extraído de [atualizarUI] (chips de capítulos). */
    private fun atualizarChipsCapitulos(texto: TextoExtraido, ativo: Int) {
        // Chips de capítulos — só reconstrói quando a lista de capítulos muda
        if (texto.capitulos.isNotEmpty()) {
            scrollChips.visibility = View.VISIBLE
            if (ultimosCapitulosChips !== texto.capitulos) {
                ultimosCapitulosChips = texto.capitulos
                atualizarChips(texto.capitulos, ativo)
            } else {
                // Apenas atualiza a seleção visual sem recriar os chips
                for (i in 0 until chipGroup.childCount) {
                    (chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip)
                        ?.isChecked = capitulosSelecionados.contains(i)
                }
            }
        }
    }

    /** Extraído de [atualizarUI] (banner de limpeza). */
    private fun atualizarPillStatusLeitura(state: ReaderUiState, texto: TextoExtraido) {
        // ── Pill de status (banner de limpeza) ────────────────────────────────
        when {
            state.limpandoTexto -> {
                progressBar.visibility = View.VISIBLE
                bannerOtimizado.visibility = View.GONE
            }
            state.textoLimpo != null -> {
                progressBar.visibility = View.GONE
                if (bannerOtimizado.visibility != View.VISIBLE) {
                    bannerOtimizado.alpha = 0f
                    bannerOtimizado.visibility = View.VISIBLE
                    bannerOtimizado.animate().alpha(1f).setDuration(DURACAO_FADE_BANNER_MS).start()
                }
                tvBannerInfo.text = getString(
                    R.string.banner_info_trechos_capitulos,
                    state.relatorioLinhasRemovidas,
                    texto.capitulos.size
                )
            }
            else -> {
                bannerOtimizado.visibility = View.GONE
            }
        }
    }

    /** Extraído de [atualizarUI] (barra de modo ativo). */
    private fun atualizarBarraModoAtivo(state: ReaderUiState) {
        // ── Barra de modo ativo ────────────────────────────────────────────────
        val emModoEspecial = state.modoEdicao || state.mostrandoAlteracoes
        layoutBarraModo.visibility = if (emModoEspecial) View.VISIBLE else View.GONE
        if (emModoEspecial) {
            tvModoAtivo.text = if (state.modoEdicao)
                getString(R.string.modo_edicao_label)
            else
                getString(R.string.modo_comparacao_label)
        }
    }

    /** Extraído de [atualizarUI] (modo do adapter + atualização). */
    private fun atualizarAdapterDeLeitura(state: ReaderUiState, texto: TextoExtraido): List<Paragrafo> {
        // ── Modo do adapter ────────────────────────────────────────────────────
        val modoAdapter = when {
            state.modoEdicao          -> AdapterModo.EDICAO
            state.mostrandoAlteracoes -> AdapterModo.ALTERACOES
            else                      -> AdapterModo.LEITURA
        }

        // Atualiza adapter
        val paragrafosExibidos = state.textoLimpo?.paragrafos ?: texto.paragrafos
        if (ultimoTextoExibido !== paragrafosExibidos) {
            ultimoTextoExibido = paragrafosExibidos
            guidedPlayerManager.setParagraphs(paragrafosExibidos)
            prepararAtribuicaoDeFalantes(paragrafosExibidos)
            marcacoesController.carregarDestaques()
        }
        adapter.atualizarEstado(
            paragrafos          = paragrafosExibidos,
            selecaoInicio       = state.selecaoInicio,
            selecaoFim          = state.selecaoFim,
            syncAtual           = state.paragrafoSyncAtual,
            progressPct         = state.progressPct,
            tamanhoFonte        = state.tamanhoFonte,
            espacamento         = state.espacamento,
            tema                = state.tema,
            modo                = modoAdapter,
            paragrafosOriginais = state.textoOriginalBruto?.paragrafos ?: emptyList(),
            fonteSerifada       = state.fonteSerifada
        )
        return paragrafosExibidos
    }

    /** Analisa localmente sem bloquear a abertura nem a primeira fala; o resultado fica por livro. */
    private fun prepararAtribuicaoDeFalantes(paragrafos: List<Paragrafo>) {
        val textos = paragrafos.map { it.texto }
        if (textos == speakerAnalysisParagraphs) return
        speakerAnalysisParagraphs = textos
        speakerAnalysisJob?.cancel()
        val identidadeLivro = caminhoArquivo
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragraph -> paragraph.texto } == textos }
            ?.capitulos
            .orEmpty()
        speakerAnalysisJob = lifecycleScope.launch {
            val (resultado, generos) = withContext(Dispatchers.IO) {
                val analisado = speakerAttributionStore.load(identidadeLivro, textos)
                    ?: run {
                        // Reanálise (livro novo ou algoritmo atualizado): mantém as vozes já escolhidas.
                        val vozes = speakerAttributionStore.previousVoices(identidadeLivro, textos)
                        val novo = OfflineSpeakerAttributor.analyze(
                            textos,
                            aiOverrides = speakerAiStore.load(identidadeLivro, textos).labels,
                            edits = speakerEditsStore.load(identidadeLivro, textos)
                        ).let { resultado ->
                            if (vozes.isEmpty()) resultado else resultado.copy(
                                speakers = resultado.speakers.map { it.copy(voiceId = vozes[it.id] ?: it.voiceId) }
                            )
                        }
                        speakerAttributionStore.save(identidadeLivro, textos, novo, capitulos)
                        novo
                    }
                analisado to analisado.speakers.associate {
                    it.id to SpeakerGenderInferrer.infer(it.name, textos)
                }
            }
            val textoAtual = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }
            if (caminhoArquivo == identidadeLivro && textoAtual == textos) {
                speakerAttribution = resultado
                speakerGenders = generos
                guidedPlayerManager.setSpeakerAttribution(resultado)
                avisarPersonagensEncontradosSeNecessario(resultado)
            }
        }
    }

    /** "Preparar capítulo": pré-sintetiza o capítulo do ponto de leitura atual (ver GuidedPlayerManager). */
    private fun prepararCapituloAtual() {
        val andamento = com.jonjonesbr.audiobookgen.service.PreRenderBridge.state.value
        if (andamento != null && !andamento.terminou) {
            Toast.makeText(this, R.string.prerender_already, Toast.LENGTH_SHORT).show()
            return
        }
        val texto = viewModel.textoEfetivo() ?: return
        if (texto.paragrafos.isEmpty()) return
        val inicio = guidedReadingBarController.obterIndiceInicioLeitura().coerceIn(0, texto.paragrafos.lastIndex)
        val capitulo = texto.capitulos.getOrNull(texto.paragrafos[inicio].indiceCapitulo)
        val fim = (capitulo?.indiceParagrafoFim ?: (inicio + PREPARO_PARAGRAFOS_SEM_CAPITULO))
            .coerceIn(inicio, texto.paragrafos.lastIndex)
        val titulo = capitulo?.titulo ?: File(caminhoArquivo).nameWithoutExtension
        val unidades = guidedPlayerManager.contarUnidadesParaPreparo(inicio, fim)
        if (unidades == 0) {
            Toast.makeText(this, R.string.prerender_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.prerender_confirm_title)
            .setMessage(getString(R.string.prerender_confirm_message, unidades, titulo))
            .setPositiveButton(R.string.prerender_confirm_start) { _, _ ->
                guidedPlayerManager.prepararCapitulo(inicio, fim, titulo)
                Toast.makeText(this, R.string.prerender_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Abre o painel pedido pela tela do livro (uma vez; o extra é consumido para não repetir na rotação). */
    private fun executarAcaoInicialSeNecessario() {
        val acao = intent.getStringExtra(EXTRA_ACAO_INICIAL) ?: return
        intent.removeExtra(EXTRA_ACAO_INICIAL)
        when (acao) {
            ACAO_INICIAL_CAPITULOS -> mostrarBottomSheetCapitulos()
            ACAO_INICIAL_PERSONAGENS -> abrirConfiguracaoVozesPersonagens()
            ACAO_INICIAL_PRONUNCIA -> PronunciationDialog.showManager(
                this, ::ouvirPronuncia,
                onSugerirIa = {
                    val paragrafos = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }.orEmpty()
                    PronunciationAiFlow.start(this, paragrafos)
                }
            )
        }
    }

    private fun abrirConfiguracaoVozesPersonagens() {
        val current = speakerAttribution
        if (current != null) {
            mostrarListaVozesPersonagens(current)
            return
        }
        lifecycleScope.launch {
            speakerAnalysisJob?.join()
            val result = speakerAttribution
            if (result == null || result.speakers.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this@ReaderActivity)
                    .setTitle(R.string.character_voices_title)
                    .setMessage(R.string.character_voices_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                mostrarListaVozesPersonagens(result)
            }
        }
    }

    private fun mostrarListaVozesPersonagens(result: SpeakerAttributionResult) {
        if (result.speakers.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.character_voices_title)
                .setMessage(R.string.character_voices_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = result.speakers.map { speaker ->
            getString(
                R.string.character_voice_assignment_item,
                speaker.name,
                speaker.utteranceCount,
                nomeVozPersonagem(speaker),
                rotuloGenero(speaker)
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_voices_title)
            .setItems((labels + getString(R.string.character_ai_action)).toTypedArray()) { _, position ->
                val personagem = result.speakers.getOrNull(position)
                if (personagem != null) mostrarOpcoesVozPersonagem(result, personagem) else confirmarAnaliseComIa()
            }
            .setPositiveButton(R.string.character_voices_suggest) { _, _ -> sugerirVozesPersonagens(result) }
            .setNeutralButton(R.string.character_voices_pronunciation) { _, _ -> mostrarPronunciaDosNomes(result) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Análise de personagens por IA (Gemini), só por ação explícita ────────────────────────────

    private fun confirmarAnaliseComIa() {
        val cliente = AiSupport.client(this)
        if (cliente == null) {
            AiSupport.explainMissing(this)
            return
        }
        val textos = speakerAnalysisParagraphs ?: return
        val estado = speakerAiStore.load(caminhoArquivo, textos)
        val lotes = SpeakerAiPlanner.planBatches(textos, estado.doneParagraphs)
        if (lotes.isEmpty()) {
            Toast.makeText(this, R.string.character_ai_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val paragrafos = lotes.sumOf { it.classify.size }
        val caracteres = lotes.sumOf { lote -> (lote.classify + lote.context).sumOf { textos[it].length } }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_ai_confirm_title)
            .setMessage(
                getString(
                    R.string.character_ai_confirm_message, paragrafos,
                    getString(R.string.character_ai_size_kb, (caracteres / 1024).coerceAtLeast(1)), lotes.size
                ) + "\n\n" + getString(R.string.character_ai_model_line, rotuloModeloIa())
            )
            .setPositiveButton(R.string.character_ai_send) { _, _ -> executarAnaliseComIa(cliente, textos, estado) }
            .setNeutralButton(R.string.ia_change) { _, _ -> opcoesDaIa() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rotuloModeloIa(): String =
        if (AiSupport.provider(this) == com.jonjonesbr.audiobookgen.domain.AiProvider.GEMINI)
            appPrefs.geminiModeloAnalise.ifBlank { getString(R.string.character_ai_model_auto) }
        else AiSupport.label(this)

    /** Trocar de IA e, no Gemini, também o modelo. */
    private fun opcoesDaIa() {
        val itens = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.ia_provider_title) to { AiSupport.chooseProvider(this) { confirmarAnaliseComIa() } }
        )
        if (AiSupport.provider(this) == com.jonjonesbr.audiobookgen.domain.AiProvider.GEMINI) {
            GeminiSpeakerClient.parseKeys(SecurePreferences.getGeminiKeys(this)).firstOrNull()?.let { chave ->
                itens += getString(R.string.character_ai_model_change) to { escolherModeloIa(chave) }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ia_change)
            .setItems(itens.map { it.first }.toTypedArray()) { _, indice -> itens[indice].second() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .show()
    }

    /** Escolha do modelo: automático, um da lista que a API libera para a chave, ou um nome digitado. */
    private fun escolherModeloIa(chave: String) {
        val carregando = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_ai_model_title)
            .setMessage(R.string.character_ai_model_loading)
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .show()
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { runCatching { GeminiSpeakerClient.listModels(chave) } }
            if (!carregando.isShowing) return@launch
            carregando.dismiss()
            val modelos = resultado.getOrDefault(emptyList())
            resultado.exceptionOrNull()?.let {
                Toast.makeText(this@ReaderActivity, getString(R.string.character_ai_model_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
            val opcoes = listOf(getString(R.string.character_ai_model_auto)) + modelos +
                getString(R.string.character_ai_model_custom)
            androidx.appcompat.app.AlertDialog.Builder(this@ReaderActivity)
                .setTitle(R.string.character_ai_model_title)
                .setItems(opcoes.toTypedArray()) { _, posicao ->
                    when (posicao) {
                        0 -> { appPrefs.geminiModeloAnalise = ""; confirmarAnaliseComIa() }
                        opcoes.lastIndex -> digitarModeloIa()
                        else -> { appPrefs.geminiModeloAnalise = opcoes[posicao]; confirmarAnaliseComIa() }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
                .show()
        }
    }

    private fun digitarModeloIa() {
        val campo = android.widget.EditText(this).apply {
            hint = getString(R.string.character_ai_model_custom_hint)
            setText(appPrefs.geminiModeloAnalise)
            setSingleLine()
        }
        val nota = android.widget.TextView(this).apply {
            text = getString(R.string.character_ai_model_note)
            textSize = 12f
        }
        val conteudo = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(campo)
            addView(nota)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_ai_model_custom)
            .setView(conteudo)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                appPrefs.geminiModeloAnalise = campo.text.toString().trim().removePrefix("models/")
                confirmarAnaliseComIa()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .show()
    }

    private fun executarAnaliseComIa(cliente: com.jonjonesbr.audiobookgen.domain.AiTextClient, textos: List<String>, inicial: SpeakerAiStore.State) {
        val identidade = caminhoArquivo
        val barra = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val texto = android.widget.TextView(this)
        val conteudo = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(texto)
            addView(barra)
        }
        val dialogo = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_ai_progress_title)
            .setView(conteudo)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> speakerAiJob?.cancel() }
            .show()
        var ultimo = inicial
        speakerAiJob?.cancel()
        speakerAiJob = lifecycleScope.launch {
            var mensagem: String? = null
            try {
                val elenco = speakerAttribution?.speakers?.map { it.name }.orEmpty()
                SpeakerAiRunner(cliente).run(
                    paragraphs = textos,
                    initial = inicial,
                    localCast = elenco,
                    onProgress = { p ->
                        texto.text = getString(R.string.character_ai_progress, p.batchesDone + 1, p.batchesTotal)
                        barra.progress = if (p.batchesTotal == 0) 0 else p.batchesDone * 100 / p.batchesTotal
                    },
                    onSave = { novo ->
                        ultimo = novo
                        speakerAiStore.save(identidade, textos, novo)
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                mensagem = getString(R.string.character_ai_cancelled)
            } catch (e: Exception) {
                mensagem = getString(R.string.character_ai_partial, e.message ?: "")
            }
            dialogo.dismiss()
            if (caminhoArquivo != identidade) return@launch
            val novo = withContext(Dispatchers.IO) { reaplicarAnaliseComIa(identidade, textos, ultimo) }
            if (novo != null) {
                Toast.makeText(
                    this@ReaderActivity,
                    mensagem ?: getString(R.string.character_ai_done, novo.speakers.size),
                    Toast.LENGTH_LONG
                ).show()
                mostrarListaVozesPersonagens(novo)
            }
        }
    }

    /** Refaz o mapa de falantes com as correções da IA, mantendo as vozes já escolhidas. */
    private fun reaplicarAnaliseComIa(identidade: String, textos: List<String>, estado: SpeakerAiStore.State): SpeakerAttributionResult? {
        val edicoes = speakerEditsStore.load(identidade, textos)
        if (estado.labels.isEmpty() && edicoes.isEmpty()) return null
        val vozes = speakerAttributionStore.previousVoices(identidade, textos)
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragrafo -> paragrafo.texto } == textos }?.capitulos.orEmpty()
        val novo = OfflineSpeakerAttributor.analyze(textos, aiOverrides = estado.labels, edits = edicoes).let { resultado ->
            if (vozes.isEmpty()) resultado else resultado.copy(
                speakers = resultado.speakers.map { it.copy(voiceId = vozes[it.id] ?: it.voiceId) }
            )
        }
        speakerAttributionStore.save(identidade, textos, novo, capitulos)
        val generos = novo.speakers.associate { it.id to SpeakerGenderInferrer.infer(it.name, textos) }
        runOnUiThread {
            speakerAttribution = novo
            speakerGenders = generos
            guidedPlayerManager.setSpeakerAttribution(novo)
        }
        return novo
    }

    /** Lista os nomes dos personagens detectados para o usuário ajustar como cada um é falado. */
    private fun mostrarPronunciaDosNomes(result: SpeakerAttributionResult) {
        val dicionario = com.jonjonesbr.audiobookgen.data.PronunciationStore.get(this)
        val nomes = result.speakers.map { it.name }
        val rotulos = nomes.map { nome -> dicionario.find(nome)?.let { "$nome  →  ${it.spoken}" } ?: nome }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_voices_pronunciation_title)
            .setItems(rotulos.toTypedArray()) { _, position ->
                PronunciationDialog.show(this, nomes[position], onOuvir = ::ouvirPronuncia) {
                    mostrarPronunciaDosNomes(result)
                }
            }
            .setPositiveButton(R.string.pron_manage_title) { _, _ ->
                PronunciationDialog.showManager(
                    this, ::ouvirPronuncia,
                    onSugerirIa = {
                        val paragrafos = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }.orEmpty()
                        PronunciationAiFlow.start(this, paragrafos)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rotuloGenero(speaker: DetectedSpeaker): String = getString(
        when (speakerGenders[speaker.id]) {
            SpeakerGender.MALE -> R.string.character_gender_male
            SpeakerGender.FEMALE -> R.string.character_gender_female
            else -> R.string.character_gender_unknown
        }
    )

    /** Aviso único por livro: só quando há personagens e nenhum tem voz atribuída. */
    private fun avisarPersonagensEncontradosSeNecessario(result: SpeakerAttributionResult) {
        if (result.speakers.isEmpty() || result.speakers.any { !it.voiceId.isNullOrBlank() }) return
        if (isFinishing || isDestroyed || appPrefs.avisoPersonagensVisto(caminhoArquivo)) return
        appPrefs.marcarAvisoPersonagensVisto(caminhoArquivo)
        com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.character_voices_hint, result.speakers.size),
            HINT_PERSONAGENS_MS
        ).setAction(R.string.character_voices_hint_action) {
            mostrarListaVozesPersonagens(speakerAttribution ?: result)
        }.show()
    }

    /** Sugere (não aplica) vozes prontas do motor do narrador; o usuário confirma antes de salvar. */
    private fun sugerirVozesPersonagens(result: SpeakerAttributionResult) {
        val (narratorVoice, motorSalvo) = guidedPlayerManager.vozEMotorDoLivro()
        val engine = VoiceCatalog.effectiveEngine(narratorVoice, motorSalvo)
        lifecycleScope.launch {
            val sugestoes = withContext(Dispatchers.IO) {
                val candidatas = vozesParaPersonagens(narratorVoice, engine)
                val idiomaNarrador = (candidatas.firstOrNull { it.id == narratorVoice } ?: VoiceCatalog.findAny(narratorVoice))
                    ?.language
                SpeakerVoiceSuggester.suggest(
                    speakers = result.speakers,
                    genders = speakerGenders,
                    candidates = candidatas,
                    narratorVoiceId = narratorVoice,
                    narratorLanguage = idiomaNarrador,
                    narratorEngine = engine,
                    isReady = ::vozUtilizavelAgora
                )
            }
            val comVoz = sugestoes.filter { it.voice != null }
            if (comVoz.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this@ReaderActivity)
                    .setTitle(R.string.character_voices_suggest_title)
                    .setMessage(R.string.character_voices_suggest_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val resumo = sugestoes.joinToString("\n") { s ->
                getString(
                    R.string.character_voices_suggest_line,
                    s.speaker.name,
                    rotuloGenero(s.speaker),
                    s.voice?.name ?: getString(R.string.character_voices_suggest_no_voice)
                )
            }
            androidx.appcompat.app.AlertDialog.Builder(this@ReaderActivity)
                .setTitle(R.string.character_voices_suggest_title)
                .setMessage(getString(R.string.character_voices_suggest_message, resumo))
                .setPositiveButton(R.string.character_voices_suggest_apply) { _, _ ->
                    aplicarVozesPersonagens(result, comVoz.associate { it.speaker.id to it.voice?.id })
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> mostrarListaVozesPersonagens(result) }
                .show()
        }
    }

    private fun mostrarOpcoesVozPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val textos = speakerAnalysisParagraphs.orEmpty()
        val edicoes = speakerEditsStore.load(caminhoArquivo, textos)
        val acoes = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.character_voice_choose) to { abrirSeletorVozPersonagem(result, speaker) },
            getString(R.string.character_voice_use_narrator) to { salvarVozPersonagem(result, speaker.id, null) },
            getString(R.string.character_edit_lines) to { mostrarFalasDoPersonagem(result, speaker) },
            getString(R.string.character_edit_merge) to { escolherFusaoDePersonagem(result, speaker) }
        )
        val fundidos = edicoes.aliases.filter { OfflineSpeakerAttributor.idOf(it.value) == speaker.id }.keys
        if (fundidos.isNotEmpty()) {
            acoes += getString(R.string.character_edit_unmerge, fundidos.size) to {
                aplicarEdicaoManual { edits -> fundidos.fold(edits) { acc, id -> acc.withoutMerge(id) } }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.character_voice_assignment_item,
                    speaker.name,
                    speaker.utteranceCount,
                    nomeVozPersonagem(speaker),
                    rotuloGenero(speaker)
                )
            )
            .setItems(acoes.map { it.first }.toTypedArray()) { _, indice -> acoes[indice].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Correção manual de personagens (B4): conferir falas, corrigir uma fala, fundir nomes ─────────

    private fun mostrarFalasDoPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val textos = speakerAnalysisParagraphs ?: return
        val falas = SpeechLines.of(result, textos, speaker.id)
        if (falas.isEmpty()) {
            Toast.makeText(this, R.string.character_edit_lines_empty, Toast.LENGTH_LONG).show()
            return
        }
        val rotulos = falas.map { getString(R.string.character_edit_excerpt, it.paragraphIndex + 1, it.text.take(EXCERTO_FALA_MAX)) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.character_edit_lines_title, speaker.name))
            .setItems(rotulos.toTypedArray()) { _, indice -> escolherQuemFala(result, speaker, falas[indice]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> mostrarOpcoesVozPersonagem(result, speaker) }
            .show()
    }

    private fun escolherQuemFala(result: SpeakerAttributionResult, atual: DetectedSpeaker, fala: com.jonjonesbr.audiobookgen.domain.SpeechLine) {
        val outros = result.speakers.filter { it.id != atual.id }
        val opcoes = outros.map { it.name } + getString(R.string.character_edit_narrator) + getString(R.string.character_edit_other)
        // setMessage e setItems não convivem: a fala aparece no título.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.character_edit_who_title) + "\n" + fala.text.take(EXCERTO_FALA_MAX))
            .setItems(opcoes.toTypedArray()) { _, indice ->
                when {
                    indice < outros.size -> corrigirFala(fala, outros[indice].name)
                    indice == outros.size -> corrigirFala(fala, OfflineSpeakerAttributor.AI_NARRATOR)
                    else -> digitarNomeDaFala(fala)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun digitarNomeDaFala(fala: com.jonjonesbr.audiobookgen.domain.SpeechLine) {
        val campo = android.widget.EditText(this).apply {
            hint = getString(R.string.character_edit_other_hint)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.character_edit_other)
            .setView(android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(campo) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nome = campo.text.toString().trim()
                if (nome.isNotEmpty()) corrigirFala(fala, nome)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun corrigirFala(fala: com.jonjonesbr.audiobookgen.domain.SpeechLine, nome: String) {
        aplicarEdicaoManual { it.withLine(fala.paragraphIndex, fala.ordinal, nome) }
    }

    private fun escolherFusaoDePersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val outros = result.speakers.filter { it.id != speaker.id }
        if (outros.isEmpty()) {
            Toast.makeText(this, R.string.character_edit_merge_none, Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.character_edit_merge_title, speaker.name))
            .setItems(outros.map { it.name }.toTypedArray()) { _, indice ->
                val destino = outros[indice]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(getString(R.string.character_edit_merge_confirm, speaker.name, destino.name))
                    .setPositiveButton(android.R.string.ok) { _, _ -> aplicarEdicaoManual { it.withMerge(speaker.id, destino.name) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Grava a correção, refaz a análise (mantendo as vozes já escolhidas) e reabre a lista de personagens. */
    private fun aplicarEdicaoManual(mudanca: (SpeakerEdits) -> SpeakerEdits) {
        val textos = speakerAnalysisParagraphs ?: return
        val identidade = caminhoArquivo
        lifecycleScope.launch {
            val novo = withContext(Dispatchers.IO) {
                speakerEditsStore.save(identidade, textos, mudanca(speakerEditsStore.load(identidade, textos)))
                reaplicarAnaliseComIa(identidade, textos, speakerAiStore.load(identidade, textos))
            }
            if (novo != null && caminhoArquivo == identidade) {
                Toast.makeText(this@ReaderActivity, R.string.character_edit_done, Toast.LENGTH_SHORT).show()
                mostrarListaVozesPersonagens(novo)
            }
        }
    }

    private fun nomeVozPersonagem(speaker: DetectedSpeaker): String {
        val voiceId = speaker.voiceId ?: return getString(R.string.character_voice_narrator)
        return VoiceCatalog.findAny(voiceId)?.name
            ?: AndroidVoiceId.decode(voiceId)?.second
            ?: voiceId
    }

    /** Vozes oferecidas aos personagens: TODOS os motores (a síntese resolve o motor de cada voz). */
    private suspend fun vozesParaPersonagens(narratorVoice: String, engine: String): List<VoiceOption> {
        val doNarradorAndroid = if (engine == "android") {
            val packageName = AndroidVoiceId.decode(narratorVoice)?.first
            if (packageName == null) emptyList() else AndroidTtsEngineCache.listVoices(this, packageName)
        } else {
            emptyList()
        }
        return VoiceCatalog.forVoiceSelection() + doNarradorAndroid
    }

    /** Voz utilizável agora: pacote local baixado ou, para nuvem com chave, chave configurada. */
    private fun vozUtilizavelAgora(voz: VoiceOption): Boolean = when (voz.engine) {
        "gemini" -> SecurePreferences.getGeminiKeys(this).isNotBlank()
        "elevenlabs" -> !SecurePreferences.getElevenLabsKey(this).isNullOrBlank()
        else -> VoiceCatalog.modeloProntoParaVoz(voz.id, this)
    }

    private fun abrirSeletorVozPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        // Voz/motor do NARRADOR deste livro ("Voz e velocidade deste livro"), não os globais.
        val (narratorVoice, motorSalvo) = guidedPlayerManager.vozEMotorDoLivro()
        val engine = VoiceCatalog.effectiveEngine(narratorVoice, motorSalvo)
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) { vozesParaPersonagens(narratorVoice, engine) }
            if (voices.isEmpty()) {
                Toast.makeText(this@ReaderActivity, R.string.character_voices_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            VoiceBottomSheet().apply {
                configure(
                    voices = voices,
                    selectedVoiceId = speaker.voiceId ?: narratorVoice,
                    onVoiceSelected = { voice ->
                        speakerVoiceDownloadFlow.verificarEBaixarSeNecessario(voice) {
                            salvarVozPersonagem(result, speaker.id, voice.id)
                        }
                    },
                    restorable = false
                )
            }.show(supportFragmentManager, "speaker_voice_${speaker.id}")
        }
    }

    private fun salvarVozPersonagem(
        result: SpeakerAttributionResult,
        speakerId: String,
        voiceId: String?
    ) = aplicarVozesPersonagens(result, mapOf(speakerId to voiceId))

    /** Salva de uma vez as vozes de vários personagens (id do personagem → id da voz, null = narrador). */
    private fun aplicarVozesPersonagens(
        result: SpeakerAttributionResult,
        vozesPorPersonagem: Map<String, String?>
    ) {
        val updated = result.copy(
            speakers = result.speakers.map {
                if (it.id in vozesPorPersonagem) it.copy(voiceId = vozesPorPersonagem[it.id]) else it
            }
        )
        val paragraphs = speakerAnalysisParagraphs ?: return
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragraph -> paragraph.texto } == paragraphs }
            ?.capitulos
            .orEmpty()
        if (!speakerAttributionStore.save(
                caminhoArquivo,
                paragraphs,
                updated,
                capitulos
            )
        ) {
            Log.w("ReaderActivity", "Não foi possível persistir o mapeamento de voz dos personagens")
        }
        speakerAttribution = updated
        guidedPlayerManager.setSpeakerAttribution(updated)
        Toast.makeText(this, R.string.character_voice_saved, Toast.LENGTH_SHORT).show()
        mostrarListaVozesPersonagens(updated)
    }

    /** Extraído de [atualizarUI] (contador da busca). */
    private fun atualizarContadorBusca(state: ReaderUiState) {
        // ── Busca ──────────────────────────────────────────────────────────────
        val totalResultados = state.buscaResultados.size
        layoutBuscaReader.visibility = if (state.buscaVisivel) View.VISIBLE else View.GONE
        if (totalResultados > 0) {
            tvBuscaContador.visibility = View.VISIBLE
            tvBuscaContador.text = getString(R.string.busca_contador, state.buscaIndiceAtual + 1, totalResultados)
        } else if (state.buscaQuery.isNotEmpty()) {
            tvBuscaContador.visibility = View.VISIBLE
            tvBuscaContador.text = getString(R.string.busca_sem_resultados)
        } else {
            tvBuscaContador.visibility = View.GONE
        }
    }

    /** Extraído de [atualizarUI] (restauração da posição de leitura salva). */
    private fun restaurarPosicaoLeituraSeNecessario(paragrafosExibidos: List<Paragrafo>) {
        // Restaura a posição de leitura salva — apenas uma vez, no carregamento inicial,
        // e nunca quando a leitura guiada vai iniciar sozinha (ela faz seu próprio scroll).
        if (!scrollRestaurado && paragrafosExibidos.isNotEmpty()) {
            scrollRestaurado = true
            val guidedIdx = guidedPlayerManager.state.value.index
            if (reconectandoGuided && guidedIdx in 0 until paragrafosExibidos.size) {
                // Reconexão: rola para o parágrafo em execução, não para o scroll salvo.
                recyclerView.post {
                    (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(guidedIdx, 0)
                }
            } else if (!intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false) &&
                !intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false)) {
                val pos = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)
                    .getInt("reader_scroll_$caminhoArquivo", 0)
                if (pos in 1 until paragrafosExibidos.size) {
                    recyclerView.post {
                        (recyclerView.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, 0)
                    }
                }
            }
        }
    }

    /** Extraído de [atualizarUI] (scroll automático da leitura guiada). */
    private fun sincronizarScrollAutomatico(state: ReaderUiState) {
        val paragrafoMudouDesdeUltimoSync =
            state.paragrafoSyncAtual != guidedReadingBarController.ultimoScrollSyncStandard
        val podeRolarAutomaticamente = !guidedReadingBarController.scrollManual && state.syncAtivo &&
            state.paragrafoSyncAtual >= 0 && paragrafoMudouDesdeUltimoSync
        if (podeRolarAutomaticamente) {
            guidedReadingBarController.ultimoScrollSyncStandard = state.paragrafoSyncAtual
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.smoothScrollToPosition(recyclerView, null, state.paragrafoSyncAtual)
        }
    }

    /** Extraído de [atualizarUI] (tema de fundo do leitor). */
    private fun aplicarTemaFundo(state: ReaderUiState) {
        val corFundo = when (state.tema) {
            TemaLeitura.CLARO  -> Color.parseColor("#F8F8F8")
            TemaLeitura.ESCURO -> Color.parseColor("#1A1A1A")
            TemaLeitura.SEPIA  -> Color.parseColor("#F5ECD7")
            TemaLeitura.OLED   -> Color.parseColor("#000000")
        }
        findViewById<View>(R.id.readerRoot).setBackgroundColor(corFundo)
    }

    /** Extraído de [atualizarUI] (margem horizontal da lista de parágrafos). */
    private fun aplicarMargemParagrafos(state: ReaderUiState) {
        if (margemNivelAplicado != state.margemNivel) {
            margemNivelAplicado = state.margemNivel
            val margemDp = MARGEM_NIVEL_DP.getOrElse(state.margemNivel) { MARGEM_NIVEL_DP[MARGEM_NIVEL_PADRAO] }
            val margemPx = dpToPx(margemDp)
            recyclerView.setPadding(margemPx, recyclerView.paddingTop, margemPx, recyclerView.paddingBottom)
        }
    }

    /**
     * % do livro lido + tempo restante estimado no capítulo (T3.3). Usa o parágrafo em
     * sincronia da leitura guiada quando ativa; senão o primeiro parágrafo visível na tela
     * (chamado também pelo listener de scroll — por isso lê o estado direto do ViewModel,
     * não recebe [state] por parâmetro). Só recalcula quando o parágrafo de referência muda,
     * para não custar nada nos ticks de 20fps da leitura guiada.
     */
    /**
     * Parágrafo de referência pra leitura manual: o em sincronia da leitura guiada quando
     * ativa; senão o primeiro parágrafo visível na tela. Usado tanto pra progresso de leitura
     * quanto pra marcadores — extraído pra não duplicar a lógica (era duplicada, com um bug:
     * marcadores usavam `findFirstCompletelyVisibleItemPosition`, que retorna -1 sempre que
     * nenhum parágrafo cabe inteiro na tela — comum com parágrafos longos — e o marcador
     * acabava sendo salvo no início do livro em vez da posição real).
     */
    private fun paragrafoAtualVisivel(): Int? {
        val state = viewModel.uiState.value
        return when {
            state.texto == null -> null
            state.paragrafoSyncAtual >= 0 -> state.paragrafoSyncAtual
            else -> (recyclerView.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?.takeIf { it >= 0 }
        }
    }

    private fun atualizarProgressoLeitura() {
        val state = viewModel.uiState.value
        val texto = state.texto
        val indiceRef = paragrafoAtualVisivel()
        if (texto == null || indiceRef == null) {
            tvProgressoLeitura.visibility = View.GONE
            marcacoesController.atualizarIconeBookmark(null)
            return
        }
        if (indiceRef == progressoIndiceAplicado) return
        progressoIndiceAplicado = indiceRef
        marcacoesController.atualizarIconeBookmark(indiceRef)

        val textoAtivo = state.textoLimpo ?: texto
        val pct = com.jonjonesbr.audiobookgen.util.progressoLivroPct(
            textoAtivo.paragrafos, indiceRef, textoAtivo.totalChars
        )
        val minutos = com.jonjonesbr.audiobookgen.util.minutosRestantesCapitulo(
            textoAtivo.paragrafos, indiceRef, textoAtivo.capitulos
        )
        tvProgressoLeitura.text = if (minutos > 0) {
            getString(R.string.progresso_leitura_com_tempo, (pct * 100).toInt(), minutos)
        } else {
            getString(R.string.progresso_leitura_sem_tempo, (pct * 100).toInt())
        }
        tvProgressoLeitura.visibility = View.VISIBLE
    }

    private fun atualizarChips(capitulos: List<Capitulo>, ativo: Int) {
        if (false && chipGroup.childCount == capitulos.size) {
            // Só atualiza seleção
            for (i in 0 until chipGroup.childCount) {
                (chipGroup.getChildAt(i) as? Chip)?.isChecked = i == ativo
            }
            return
        }
        chipGroup.removeAllViews()
        capitulos.forEachIndexed { i, cap ->
            val chip = Chip(this).apply {
                text = cap.titulo
                isCheckable = true
                isChecked = capitulosSelecionados.contains(i)
                setOnClickListener {
                    if (isChecked) {
                        capitulosSelecionados.add(i)
                        viewModel.limparSelecao()
                    } else {
                        capitulosSelecionados.remove(i)
                    }

                    if (capitulosSelecionados.isEmpty()) {
                        viewModel.navegarParaCapitulo(i)
                        val paragIdx = viewModel.textoEfetivo()
                            ?.capitulos?.getOrNull(i)?.indiceParagrafoInicio ?: return@setOnClickListener
                        recyclerView.scrollToPosition(paragIdx)
                    } else {
                        atualizarUI(viewModel.uiState.value)
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }

    // ── Resultado ─────────────────────────────────────────────────────────────

    private fun verificarConectividadeAntesDeConverter(prosseguir: () -> Unit) {
        // O motor salvo pode divergir do que vai sintetizar de fato (voz Supertonic com
        // motor "edge" salvo, por ex.) — decide pela voz selecionada.
        val motorEfetivo = VoiceCatalog.effectiveEngine(
            appPrefs.vozSelecionada.orEmpty(), appPrefs.motorTts
        )
        // "Motor Android" só é realmente offline se já existe uma voz do dispositivo
        // configurada (ID composto "android::pkg::nome") — sem isso, motorTts="android" é um
        // estado inválido (não dá pra sintetizar nada) e não deve ser tratado como pronto.
        val temVozAndroidValida = com.jonjonesbr.audiobookgen.tts.AndroidVoiceId.isAndroidVoice(
            appPrefs.vozSelecionada.orEmpty()
        )
        val prontoOffline = when (motorEfetivo) {
            "android" -> temVozAndroidValida
            "onnx" -> true // Supertonic sintetiza offline
            "kokoro" -> VoiceCatalog.modeloProntoParaVoz(appPrefs.vozSelecionada.orEmpty(), this)
            else -> false
        }
        if (prontoOffline) {
            prosseguir()
            return
        }

        val online = com.jonjonesbr.audiobookgen.util.NetworkStatus.isOnline(this)

        if (!online) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.offline_titulo))
                .setMessage(getString(R.string.offline_mensagem))
                .setNegativeButton(getString(R.string.offline_btn_ok), null)
                .setPositiveButton(getString(R.string.offline_btn_alternar)) { _, _ ->
                    if (temVozAndroidValida) {
                        appPrefs.motorTts = "android"
                        prosseguir()
                    } else {
                        android.widget.Toast.makeText(
                            this,
                            R.string.erro_precisa_configurar_voz_dispositivo,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .show()
        } else {
            prosseguir()
        }
    }

    // ── Bottom Sheet de Aparência ─────────────────────────────────────────────

    // ── Bottom Sheet de Capítulos (T3.4 — sumário navegável) ───────────────────

    private fun mostrarBottomSheetCapitulos() {
        val texto = viewModel.textoEfetivo() ?: return
        if (texto.capitulos.isEmpty()) return

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_capitulos, null)
        dialog.setContentView(view)

        val ativo = viewModel.uiState.value.capituloAtivo
        val adapter = CapituloTocAdapter(texto.capitulos, ativo) { indice ->
            dialog.dismiss()
            viewModel.navegarParaCapitulo(indice)
            val paragIdx = texto.capitulos.getOrNull(indice)?.indiceParagrafoInicio ?: return@CapituloTocAdapter
            recyclerView.scrollToPosition(paragIdx)
        }
        view.findViewById<RecyclerView>(R.id.rvCapitulosToc).apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            this.adapter = adapter
        }

        dialog.show()
    }

    private val cloneVoiceFlow = CloneVoiceFlow(this)

    private val voiceSelectionDelegate: VoiceSelectionDelegate = VoiceSelectionDelegate(
        activity = this,
        onVoiceSelected = { voice ->
            appPrefs.vozSelecionada = voice.id
            appPrefs.motorTts = voice.engine
            guidedPlayerManager.registrarVozEscolhida(voice.id, voice.engine)

            if (voice.engine == "gemini") {
                val chave = SecurePreferences.getGeminiKeys(this@ReaderActivity)
                if (chave.isNullOrBlank()) {
                    val intent = Intent(this@ReaderActivity, SettingsActivity::class.java).apply {
                        putExtra("focar_chave_gemini", true)
                    }
                    startActivity(intent)
                }
            }
            if (voice.engine == "elevenlabs" &&
                SecurePreferences.getElevenLabsKey(this@ReaderActivity).isNullOrBlank()
            ) {
                val intent = Intent(this@ReaderActivity, SettingsActivity::class.java).apply {
                    putExtra("focar_chave_elevenlabs", true)
                }
                startActivity(intent)
            }

            val guidedIndex = guidedPlayerManager.state.value.index
            if (guidedIndex >= 0) {
                guidedReadingBarController.scrollManual = false
                guidedPlayerManager.play(guidedIndex)
            }
        },
        onVoicePreview = { voice, onFinished ->
            audiobookViewModel.pararPreview()
            if (voice.engine == "elevenlabs" &&
                SecurePreferences.getElevenLabsKey(this@ReaderActivity).isNullOrBlank()
            ) {
                Toast.makeText(
                    this@ReaderActivity,
                    R.string.toast_elevenlabs_configure_key,
                    Toast.LENGTH_LONG
                ).show()
                onFinished()
            } else {
                val ritmo = appPrefs.ritmo
                val chave = SecurePreferences.getGeminiKeys(this@ReaderActivity)
                audiobookViewModel.prepararConfiguracoesPython(voice.engine, chave, appPrefs.pausaMs)
                val previewText = viewModel.textoEfetivo()?.paragrafos
                    ?.take(PARAGRAFOS_PREVIEW)?.joinToString(" ") { it.texto }
                    ?.takeIf { it.isNotBlank() }
                    ?: com.jonjonesbr.audiobookgen.util.VoiceCatalog.previewSampleText(voice)
                lifecycleScope.launch {
                    audiobookViewModel.gerarAmostra(voice.id, ritmo, voice.engine, chave, previewText)
                    audiobookViewModel.uiState.first { !it.amostraGerando }
                    onFinished()
                }
            }
        },
        obterVozAtualId = { appPrefs.vozSelecionada.orEmpty() },
        onCloneVoiceRequested = { cloneVoiceFlow.iniciar() },
    )

    /** Toca [texto] (a pronúncia digitada) com a voz e o motor do livro. */
    private fun ouvirPronuncia(texto: String) {
        val (voz, motorSalvo) = guidedPlayerManager.vozEMotorDoLivro()
        val motor = VoiceCatalog.effectiveEngine(voz, motorSalvo)
        val chave = SecurePreferences.getGeminiKeys(this)
        audiobookViewModel.pararPreview()
        audiobookViewModel.prepararConfiguracoesPython(motor, chave, appPrefs.pausaMs)
        audiobookViewModel.ouvirTexto(voz, appPrefs.ritmo, motor, texto)
    }

    private fun compartilharTrechoAtual() {
        val state = viewModel.uiState.value
        val index = state.paragrafoSyncAtual
        val texto = viewModel.textoEfetivo()
        val paragrafos = state.textoLimpo?.paragrafos ?: texto?.paragrafos

        val paragrafo = if (index in 0 until (paragrafos?.size ?: 0)) {
            paragrafos?.get(index)
        } else {
            null
        }

        if (paragrafo == null || paragrafo.texto.trim().isEmpty()) {
            Toast.makeText(this, R.string.toast_nada_para_compartilhar, Toast.LENGTH_SHORT).show()
        } else {
            val textoCompartilhar = paragrafo.texto + getString(R.string.share_excerpt_signature)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, textoCompartilhar)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, getString(R.string.share_excerpt_chooser))
            startActivity(shareIntent)
        }
    }
}
