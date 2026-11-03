package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.ReadingStartDetector
import com.jonjonesbr.audiobookgen.domain.RegraDaRolagemDoLeitor
import com.jonjonesbr.audiobookgen.domain.RegraDeReconexaoGuiada
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
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
        const val EXTRA_TRECHO_NOME = "extra_trecho_nome"
        const val EXTRA_ACAO_RETORNO = "extra_acao_retorno"
        const val EXTRA_AUTO_START_GUIDED = "extra_auto_start_guided"
        private const val STATE_AUTO_START_RESOLVIDO = "state_auto_start_resolvido"
        private const val STATE_CONTINUAR_GUIADA_RESOLVIDO = "state_continuar_guiada_resolvido"
        private const val STATE_TOKEN_SESSAO_GUIADA = "state_token_sessao_guiada"
        // Continua a leitura guiada direto na posição salva (sem diálogo de ponto de início).
        const val EXTRA_CONTINUAR_GUIADA = "extra_continuar_guiada"
        /** Painel a abrir assim que o texto carregar (vindo da tela do livro): uma das `ACAO_INICIAL_*`. */
        const val EXTRA_ACAO_INICIAL = "extra_acao_inicial"
        const val ACAO_INICIAL_CAPITULOS = "capitulos"
        const val ACAO_INICIAL_PERSONAGENS = "personagens"
        const val ACAO_INICIAL_PRONUNCIA = "pronuncia"
        /**
         * Modo "Ler sem áudio": abre o livro como leitor puro, sem leitura guiada, conversão, voz nem
         * análise de personagens (nada de GuidedPlayerManager). Busca, capítulos, marcadores, destaques,
         * aparência e posição de leitura continuam iguais.
         */
        const val EXTRA_SOMENTE_LEITURA = "extra_somente_leitura"
        /** Caminhos (ArrayList<String>) dos PRÓXIMOS arquivos a ler em sequência, em ordem,
         *  NÃO incluindo o arquivo atual — usado pelo botão "Ler tudo" da fila de conversão
         *  (tela Conversões). Quem avança é o GuidedPlayerManager: ele guarda a fila e troca de
         *  livro sozinho, inclusive com a tela apagada. Esta Activity só segue o livro que está
         *  sendo narrado (ver abrirLeitorDoLivroDaFila). */
        const val EXTRA_FILA_SEQUENCIA = "extra_fila_sequencia"
        const val ACAO_CONVERTER = "converter"
        const val ACAO_MANTER_EDICOES = "manter_edicoes"
        /** Ação de retorno do botão "Converter assim mesmo" (estado de erro do leitor): devolve o
         *  arquivo ORIGINAL pra MainActivity converter pelo pipeline tolerante (que não precisa de
         *  estrutura de capítulos) — o resultado pode sair sem divisão por capítulo. */
        const val ACAO_CONVERTER_SEM_ESTRUTURA = "converter_sem_estrutura"
        private const val LARGURA_MAXIMA_LEITURA_DP = 720
    }

    private val viewModel: ReaderViewModel by viewModels()
    private val audiobookViewModel: AudiobookViewModel by viewModels()

    // Views
    private lateinit var btnVoltar: ImageButton
    private lateinit var tvTitulo: TextView
    private lateinit var btnOpcoes: ImageButton
    private lateinit var btnConverterAssim: Button
    private lateinit var btnTentarDeNovo: Button
    private lateinit var recyclerView: RecyclerViewParagrafos
    // FAB único de conversão (ver TelaDoLeitor.kt e atualizarFabs()).
    private lateinit var fabConverterPrincipal: ExtendedFloatingActionButton
    // "Voltar à narração": no lugar do FAB de conversão, só no modo manual da guiada (ver atualizarFabs()).
    private lateinit var fabVoltarNarracao: ExtendedFloatingActionButton

    // Status pill (banner de limpeza): a view fica aqui; quem mostra é o ReaderTelaController.
    private lateinit var bannerOtimizado: android.view.ViewGroup

    private lateinit var btnConcluirModo: Button
    private lateinit var btnBookmark: android.widget.ImageButton

    private lateinit var adapter: ParagrafoAdapter
    private var caminhoArquivo: String = ""
    private var arquivoTrechoTemp: File? = null
    private var preservarArquivoTrechoTemp = false
    private val dialogos = DialogosAbertos(this)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog =
        dialogos.mostrar(this)
    // Marca de tempo de quando a tela ficou visível, para creditar minutos lidos nas
    // estatísticas ao sair (T4.5) — 0L quando a tela não está visível.
    private var telaVisivelDesdeMs = 0L
    private val capitulosSelecionados = linkedSetOf<Int>()
    private val exportarTextoLimpo = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) conversaoController.exportarTextoLimpo(uri) }

    private val exportarCapitulosPasta = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) conversaoController.exportarCapitulosPara(uri) }

    /** Nulo no modo "Ler sem áudio" (o manager nunca é criado). Todo acesso trata o nulo. */
    private var guidedPlayerManager: GuidedPlayerManager? = null
    private val somenteLeitura: Boolean by lazy { intent.getBooleanExtra(EXTRA_SOMENTE_LEITURA, false) }
    private var ultimoTextoExibido: List<Paragrafo>? = null
    private var autoStartGuidedTriggered = false
    private var reconectandoGuided = false
    private var continuarGuiadaTriggered = false
    // A fila "Ler tudo" já abriu o livro seguinte nesta tela: ela está fechando e não grava mais nada.
    private var livroTrocadoPelaFila = false
    private val appPrefs by lazy { com.jonjonesbr.audiobookgen.data.AppPrefs(this) }

    // Barra de capítulos, progresso de leitura e painel de capítulos (rb-7). Lazy: só é montado no vincularViews.
    private val capitulosController: ReaderCapitulosController by lazy {
        ReaderCapitulosController(
            activity = this,
            viewModel = viewModel,
            views = ReaderCapitulosController.Views(
                recyclerView = recyclerView,
                barra = findViewById(R.id.barraCapitulo),
                area = findViewById(R.id.areaCapituloAtual),
                tvCapitulo = findViewById(R.id.tvCapituloAtual),
                ivAbrir = findViewById(R.id.ivAbrirCapitulos),
                anterior = findViewById(R.id.btnCapituloAnterior),
                proximo = findViewById(R.id.btnCapituloProximo),
                tvProgresso = findViewById(R.id.tvProgressoLeitura),
            ),
            capitulosSelecionados = capitulosSelecionados,
            somenteLeitura = somenteLeitura,
            callbacks = ReaderCapitulosController.Callbacks(
                obterParagrafoAtual = { rolagemController.paragrafoAtualVisivel() },
                fimDoLivroNaTela = { rolagemController.fimDoLivroNaTela() },
                obterBarraGuiada = { guidedReadingBarController },
                obterIndiceGuiada = { guidedPlayerManager?.state?.value?.index ?: -1 },
                atualizarIconeBookmark = { marcacoesController.atualizarIconeBookmark(it) },
                aoMudarSelecao = { atualizarUI(viewModel.uiState.value) },
            ),
        )
    }

    // Rolagem, teclas, posição salva e acompanhamento do audiobook (rb-6). Lazy: abre no configurarRecyclerView.
    private val rolagemController: ReaderRolagemController by lazy {
        ReaderRolagemController(
            activity = this, viewModel = viewModel, recyclerView = recyclerView,
            callbacks = ReaderRolagemController.Callbacks(
                obterBarraGuiada = { guidedReadingBarController },
                obterIndiceGuiada = { guidedPlayerManager?.state?.value?.index ?: -1 },
                obterAdapter = { if (::adapter.isInitialized) adapter else null },
                reconectandoGuiada = { reconectandoGuided },
                guiadaIniciaSozinha = {
                    intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false) ||
                        intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false)
                },
                posicaoSalva = { appPrefs.posicaoLeitura(caminhoArquivo, 0) },
                aoPararDeRolar = { capitulosController.atualizarProgressoLeitura() },
            ),
        )
    }

    private val aparenciaController: ReaderAparenciaController by lazy {
        ReaderAparenciaController(this, viewModel) { horizontal ->
            rolagemController.aplicarOrientacao(horizontal)
        }
    }

    // Voz e pronúncia do leitor (rb-14). Lazy: só é montado no primeiro uso; o manager entra só pelas lambdas.
    private val vozController: ReaderVozController by lazy {
        ReaderVozController(
            activity = this,
            viewModel = viewModel,
            audiobookViewModel = audiobookViewModel,
            callbacks = ReaderVozController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterGuidedPlayerManager = { guidedPlayerManager },
                obterParagrafoAtual = { rolagemController.paragrafoAtualVisivel() },
                obterPrimeiroNaTela = { rolagemController.primeiroParagrafoNaTela() },
                rolouParaLonge = { guidedReadingBarController?.scrollManual == true },
            ),
        )
    }

    // Pré-carregar capítulo (rb-25): o ⋮ e a barra da guiada abrem a mesma confirmação. Só religa callbacks.
    private val preparoController: ReaderPreparoController by lazy {
        ReaderPreparoController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderPreparoController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterGuidedPlayerManager = { guidedPlayerManager },
                obterParagrafoAtual = { rolagemController.paragrafoAtualVisivel() },
                tocarGuiadaDesde = { guidedReadingBarController?.playGuidedFrom(it) },
            ),
        )
    }

    /**
     * Nulo no modo "Ler sem áudio" (não há manager para a barra conectar). Não acessar antes de o manager ser
     * atribuído no onCreate.
     */
    private val guidedReadingBarController: GuidedReadingBarController? by lazy {
        guidedPlayerManager?.let { manager ->
            GuidedReadingBarController(
                activity = this,
                guidedPlayerManager = manager,
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
                    // A barra da leitura guiada e o mini player do audiobook não aparecem juntos
                    // (ver ocultarMiniPlayerAudio).
                    mostrarMiniPlayer = { reavaliarMiniPlayer() },
                    esconderMiniPlayer = { reavaliarMiniPlayer() },
                    obterCaminhoArquivo = { caminhoArquivo },
                    aoMudarDeLivroNaFila = { guidedPlayerManager?.let(::abrirLeitorDoLivroDaFila) },
                    prepararCapitulo = { preparoController.abrir() },
                    aoEncerrarGuiada = { rolagemController.aoEncerrarGuiada(it) },
                ),
            )
        }
    }
    private val marcacoesController: ReaderMarcacoesController by lazy {
        ReaderMarcacoesController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderMarcacoesController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterParagrafoAtual = { rolagemController.paragrafoAtualVisivel() },
                // Salto exato ao topo (como a navegação por capítulos): smoothScrollToPosition parava onde o item
                // "aparecia", e um segundo toque na mesma marcação levava a outro ponto do texto.
                rolarParaParagrafo = { pos ->
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
                },
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

    private val sonecaController: ReaderSonecaController by lazy {
        ReaderSonecaController(
            activity = this,
            callbacks = ReaderSonecaController.Callbacks(
                obterTokenSessaoGuided = { guidedPlayerManager?.sessionToken },
                pausarGuided = { guidedPlayerManager?.pause() },
                pausarPlayerSeTocando = {
                    if (playerService?.state?.value?.isPlaying == true) {
                        playerService?.alternarPlayPause()
                    }
                },
                onAtualizarSoneca = { rotulo, descricao ->
                    tvGuidedSoneca.text = rotulo
                    // A contagem vai só para a descrição: sem anúncio a cada segundo (a11y-5).
                    tvGuidedSoneca.contentDescription = descricao
                    tvGuidedSoneca.visibility = View.VISIBLE
                },
            ),
        )
    }

    private val personagensController: ReaderPersonagensController by lazy {
        ReaderPersonagensController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderPersonagensController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterGuidedPlayerManager = { guidedPlayerManager },
                ouvirPronuncia = vozController.ouvirNoFluxo,
            ),
        )
    }

    private val retornoController: ReaderRetornoController by lazy {
        ReaderRetornoController(
            activity = this,
            viewModel = viewModel,
            somenteLeitura = somenteLeitura,
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
                onCompartilharTrecho = {
                    val paragrafos = viewModel.uiState.value.textoLimpo?.paragrafos
                        ?: viewModel.textoEfetivo()?.paragrafos
                    val rolouParaLonge = guidedReadingBarController?.scrollManual == true
                    val paragrafo = paragrafoParaCompartilhar(
                        paragrafos,
                        guidedPlayerManager?.state?.value?.index,
                        rolouParaLonge = rolouParaLonge,
                    ) { rolagemController.paragrafoDaTela(rolouParaLonge) }
                    compartilharTrecho(this, paragrafo)
                },
                onBaixarCapitulo = { conversaoController.baixarCapitulo() },
                onBaixarLivro = { conversaoController.gerarLivroInteiro() },
                onGerarCapitulosMarcados = {
                    conversaoController.verificarConectividadeAntesDeConverter {
                        retornoController.retornarComSelecao()
                    }
                },
                onOuvirAudiobook = { conversaoController.ouvirAudiobookDesteLivro() },
                onConfigurarVozesPersonagens = { personagensController.abrirConfiguracaoVozesPersonagens() },
                onAbrirMarcacoes = { marcacoesController.mostrarDialogListarMarcacoes() },
                onAbrirPronuncia = { vozController.abrirDicionarioPronuncia() },
                onAbrirAparencia = { aparenciaController.mostrarBottomSheet() },
                onAbrirSleepTimer = { sonecaController.mostrarDialogoSleepTimer() },
                onPrepararCapitulo = { preparoController.abrir() },
                onClonarVoz = { aoEscolherVoz ->
                    aoClonarVozDoPainel = aoEscolherVoz
                    cloneVoiceFlowDoLivro.iniciar()
                },
                onPerfilDoLivroMudou = {
                    guidedPlayerManager?.recarregarPerfilDoLivro()
                    guidedReadingBarController?.atualizarRotulosDoPerfil()
                },
                verificarConectividade = { prosseguir ->
                    conversaoController.verificarConectividadeAntesDeConverter(prosseguir)
                },
            ),
        )
    }
    
    private val conversaoController: ReaderConversaoController by lazy {
        ReaderConversaoController(
            activity = this,
            viewModel = viewModel,
            audiobookViewModel = audiobookViewModel,
            callbacks = ReaderConversaoController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterGuidedPlayerManager = { guidedPlayerManager },
                obterPlayerService = { playerService },
                obterParagrafoAtual = { rolagemController.paragrafoAtualVisivel() },
                caminhoMapaDePersonagens = { personagensController.caminhoMapaDePersonagens(it) },
                aguardarAnaliseDeFalantes = { personagensController.aguardarAnaliseDeFalantes() },
            ),
        )
    }

    // Busca no texto: as views vêm do layout já inflado (vincularViews roda antes de configurarBotoes).
    private val buscaController: ReaderBuscaController by lazy {
        ReaderBuscaController(
            activity = this,
            viewModel = viewModel,
            recyclerView = recyclerView,
            views = ReaderBuscaController.Views(
                abrir = findViewById(R.id.btnBuscarReader),
                layout = findViewById(R.id.layoutBuscaReader),
                campo = findViewById(R.id.etBuscaReader),
                contador = findViewById(R.id.tvBuscaContador),
                anterior = findViewById(R.id.btnBuscaAnterior),
                proximo = findViewById(R.id.btnBuscaProximo),
                fechar = findViewById(R.id.btnBuscaFechar),
            ),
            obterAdapter = { adapter },
        )
    }

    // Tela do leitor (rb-8): carregando, erro, pílula da limpeza, barra de modo, FABs, tema, margem e dica.
    // Lazy: abre no vincularViews, logo depois do recyclerView.
    private val telaController: ReaderTelaController by lazy {
        ReaderTelaController(
            activity = this,
            recyclerView = recyclerView,
            somenteLeitura = somenteLeitura,
            views = ReaderTelaController.Views(
                barraCapitulo = findViewById(R.id.barraCapitulo),
                layoutCarregando = findViewById(R.id.layoutCarregandoReader),
                tvCarregando = findViewById(R.id.tvCarregandoReader),
                layoutErro = findViewById(R.id.layoutReaderErro),
                tvErro = findViewById(R.id.tvReaderErro),
                btnConverterAssim = findViewById(R.id.btnConverterAssim),
                fabConverter = findViewById(R.id.fabConverterPrincipal),
                fabVoltarNarracao = findViewById(R.id.fabVoltarNarracao),
                layoutGuidedPlayer = findViewById(R.id.layoutGuidedPlayer),
                banner = findViewById(R.id.bannerTextoOtimizado),
                tvBannerInfo = findViewById(R.id.tvBannerInfo),
                layoutBarraModo = findViewById(R.id.layoutBarraModo),
                tvModoAtivo = findViewById(R.id.tvModoAtivo),
                btnOpcoes = findViewById(R.id.btnReaderOpcoes),
                readerRoot = findViewById(R.id.readerRoot),
                barraSelecao = findViewById(R.id.layoutBarraSelecao),
                tvSelecao = findViewById(R.id.tvSelecaoParagrafos),
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
            autoStartGuidedTriggered && guidedReadingBarController?.dialogoInicioPendente != true)
        // "Continuar" já iniciou a guiada: ao girar a tela, não recomeça uma leitura que o usuário já fechou.
        outState.putBoolean(STATE_CONTINUAR_GUIADA_RESOLVIDO, continuarGuiadaTriggered)
        // Sessão da leitura guiada a que esta tela estava ligada (-1 se nenhuma). Só ela volta para o livro da fila.
        outState.putLong(
            STATE_TOKEN_SESSAO_GUIADA,
            guidedPlayerManager?.takeIf { GuidedPlaybackBridge.manager === it }?.sessionToken ?: -1L
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoStartGuidedTriggered = savedInstanceState?.getBoolean(STATE_AUTO_START_RESOLVIDO) ?: false
        continuarGuiadaTriggered = savedInstanceState?.getBoolean(STATE_CONTINUAR_GUIADA_RESOLVIDO) ?: false
        setContentView(R.layout.activity_reader)
        configurarMiniPlayerBotoes()

        caminhoArquivo = intent.getStringExtra(EXTRA_CAMINHO) ?: run { finish(); return }
        if (redirecionarSeAFilaAvancou(savedInstanceState)) return
        // Livro da biblioteca: registra a abertura (ordena "recentes" e alimenta o cartão Continuar).
        lifecycleScope.launch {
            com.jonjonesbr.audiobookgen.data.BibliotecaStore.marcarAberto(applicationContext, caminhoArquivo)
        }
        // Livro da biblioteca: usa o título dele (com acentos), não o nome do arquivo (Dom_Casmurro).
        val tituloCompleto = com.jonjonesbr.audiobookgen.data.BibliotecaStore.tituloPorCaminho(this, caminhoArquivo)
            ?: File(caminhoArquivo).nameWithoutExtension

        // Precisa rodar ANTES de vincularViews()/guidedReadingBarController abaixo: o
        // lazy de guidedReadingBarController lê guidedPlayerManager (nullable) no
        // primeiro acesso (configurarGuidedPlayerBotoes()). Se ainda for nulo nesse
        // momento, o lazy fixa null e a barra guiada nunca se conecta ao manager deste livro.
        val pythonUseCase = PythonEngineUseCase(applicationContext)
        conectarLeituraGuiada(savedInstanceState, tituloCompleto, pythonUseCase)

        vincularViews()
        configurarRecyclerView()
        configurarBotoes()
        guidedReadingBarController?.configurarGuidedPlayerBotoes()
        rolagemController.configurar()
        onBackPressedDispatcher.addCallback(this) { retornoController.voltar() }

        configurarTitulo(tituloCompleto)

        viewModel.carregarPreferencias(appPrefs)
        // configurarRecyclerView() já rodou com o default (vertical) — carregarPreferencias()
        // só agora carrega o valor salvo, então reaplica a orientação correta.
        rolagemController.aplicarOrientacao(viewModel.uiState.value.rolagemHorizontal)

        sincronizarMotor(pythonUseCase)

        observarViewModel()
        guidedReadingBarController?.observarGuidedPlayer()
        if (!somenteLeitura) sonecaController.observarSleepTimer()
        conversaoController.observar()

        // NÃO usar `savedInstanceState == null` aqui: após morte de processo o Android
        // recria a Activity com savedInstanceState preenchido (restaurado do disco), mas o
        // ViewModel é uma instância NOVA (sem SavedStateHandle) — o texto do livro estaria
        // vazio para sempre, sem erro nem loading, uma tela em branco. O que importa é se O
        // VIEWMODEL já tem o texto carregado (sobrevive à rotação, não à morte de processo).
        if (viewModel.uiState.value.texto == null) {
            viewModel.carregarArquivo(caminhoArquivo)
        }
    }

    /**
     * Tela recriada de um livro que a fila já deixou para trás: vai para o livro narrado, antes de criar um manager
     * para o velho. Devolve true quando abriu o livro da fila; o onCreate para aqui.
     */
    private fun redirecionarSeAFilaAvancou(savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState != null && !somenteLeitura) {
            val sessao = GuidedPlaybackBridge.manager
            if (sessao != null && savedInstanceState.getLong(STATE_TOKEN_SESSAO_GUIADA, -1L) == sessao.sessionToken &&
                RegraDeReconexaoGuiada.leitorFicouParaTras(
                    caminhoArquivo, sessao.caminhoArquivo, sessao.state.value.index, sessao.livrosDeixadosNaFila
                )
            ) {
                abrirLeitorDoLivroDaFila(sessao)
                return true
            }
        }
        return false
    }

    /** Liga o manager deste livro (ou reconecta ao que já narra este documento). Fora do modo "Ler sem áudio". */
    private fun conectarLeituraGuiada(
        savedInstanceState: Bundle?, tituloCompleto: String, pythonUseCase: PythonEngineUseCase,
    ) {
        // Modo "Ler sem áudio": nenhum manager é criado nem conectado (nem o bridge é tocado).
        if (!somenteLeitura) {
            // Reconecta ao manager em execução se já houver leitura guiada ativa para ESTE documento
            // (ex.: usuário voltou ao menu e reabriu pelo indicador/notificação). Senão, cria um novo.
            // Usa applicationContext para o manager poder sobreviver ao fim desta Activity.
            // A regra (mesmo documento e index >= 0) fica em RegraDeReconexaoGuiada, testada na JVM.
            val managerExistente = RegraDeReconexaoGuiada.managerParaReconectar(
                GuidedPlaybackBridge.manager,
                { GuidedPlaybackBridge.caminhoArquivo },
                caminhoArquivo
            ) { it.state.value.index }
            val manager: GuidedPlayerManager = if (managerExistente != null) {
                reconectandoGuided = true
                managerExistente
            } else {
                // A leitura guiada de OUTRO livro continua tocando: só abrir este livro para ler não a interrompe.
                // Ela é encerrada no primeiro play() deste livro (ver GuidedPlayerManager.play).
                GuidedPlayerManager(applicationContext, pythonUseCase)
            }
            guidedPlayerManager = manager
            manager.trackName = tituloCompleto
            manager.caminhoArquivo = caminhoArquivo
            // Reconectando a uma sessão já ativa: os campos acima foram atualizados diretamente no
            // manager (sem passar por play()), então republica a mesma escrita atômica no bridge
            // para não deixar trackName/caminho desalinhados com o token/manager já publicados.
            if (GuidedPlaybackBridge.manager === manager) {
                GuidedPlaybackBridge.activate(manager, tituloCompleto, caminhoArquivo)
            }
            // Fila "Ler tudo": a intent traz os próximos livros. Girando com a guiada tocando, vale a fila que o
            // manager já andou (a da intent original está desatualizada); abrindo de novo pela fila, vale a da intent.
            val filaDaIntent = intent.getStringArrayListExtra(EXTRA_FILA_SEQUENCIA)
            val usaFila = RegraDeReconexaoGuiada.usaFilaDaIntent(
                temFila = filaDaIntent != null,
                recriada = savedInstanceState != null,
                reconectando = reconectandoGuided,
            )
            if (usaFila) filaDaIntent?.let { manager.filaSequencia = it.toList() }
        }
    }

    private fun configurarTitulo(tituloCompleto: String) {
        tvTitulo.text = tituloCompleto
        tvTitulo.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(null)
                .setMessage(tituloCompleto)
                .setPositiveButton(R.string.dialog_ok, null)
                .exibir()
            true
        }
    }

    private fun sincronizarMotor(pythonUseCase: PythonEngineUseCase) {
        // Bug #4 fix: sincroniza motor TTS, chave e pausas para o GuidedPlayerManager
        val motorInit = appPrefs.motorTts
        val chaveInit = SecurePreferences.getGeminiKeys(this)
        val pausaMsInit = appPrefs.pausaMs
        lifecycleScope.launch {
            pythonUseCase.sincronizarConfiguracoes(motorInit, chaveInit, pausaMsInit, caminhoDoLivro = caminhoArquivo)
        }
    }

    override fun onStart() {
        super.onStart()
        telaVisivelDesdeMs = System.currentTimeMillis()
    }

    /** "Manter tela ligada": a flag vale só enquanto o leitor está visível (ver também a folha de Aparência). */
    override fun onResume() {
        super.onResume()
        aplicarManterTelaLigada(window, appPrefs.readerManterTelaLigada)
    }

    override fun onPause() {
        aplicarManterTelaLigada(window, false)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        registrarMinutosLidos()
        if (::adapter.isInitialized) retornoController.aplicarEdicoesPendentes()
        viewModel.salvarPreferencias(appPrefs)
        // Salva a posição de leitura para retomar depois: o parágrafo da leitura guiada, ativa ou recém-encerrada sem
        // rolagem manual depois (o marcador fica abaixo do topo da tela); senão o primeiro parágrafo visível.
        // Livro já trocado pela fila: o manager gravou a posição final do livro concluído;
        // esta tela não grava por cima.
        if (!livroTrocadoPelaFila && ::recyclerView.isInitialized && caminhoArquivo.isNotEmpty()) {
            rolagemController.paragrafoParaSalvar()
                ?.let {
                    // Total de parágrafos: dá a porcentagem lida (progresso = scroll / total) na biblioteca.
                    appPrefs.salvarPosicaoLeitura(
                        caminhoArquivo, it, if (::adapter.isInitialized) adapter.itemCount else 0
                    )
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recyclerView.isInitialized) rolagemController.liberar()
        val manager = guidedPlayerManager
        if (manager != null) {
            // Mantém a leitura guiada tocando em 2º plano (foreground service); só libera o
            // manager quando a leitura está parada. Reabrir o leitor reconecta ao manager vivo.
            if (manager.state.value.index < 0) {
                if (manager.preparandoCapitulo()) {
                    // O preparo do capítulo segue em segundo plano (com notificação); o manager se
                    // libera sozinho quando ele terminar.
                    manager.liberarAoFimDoPreparo = true
                } else {
                    manager.release()
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

    // Com a barra da leitura guiada na tela, o mini player do audiobook some (antes os dois apareciam juntos).
    override fun ocultarMiniPlayerAudio(): Boolean = (guidedPlayerManager?.state?.value?.index ?: -1) >= 0

    // Minutos da tela visível (ver minutosLidos, em TelaDoLeitor.kt).
    private fun registrarMinutosLidos() {
        val minutos = minutosLidos(telaVisivelDesdeMs, System.currentTimeMillis()).also { telaVisivelDesdeMs = 0L }
        if (minutos > 0) com.jonjonesbr.audiobookgen.data.StatsStore.registrarMinutosLidos(this, minutos)
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private fun vincularViews() {
        btnVoltar            = findViewById(R.id.btnReaderVoltar)
        tvTitulo             = findViewById(R.id.tvReaderTitulo)
        btnOpcoes            = findViewById(R.id.btnReaderOpcoes)
        btnConverterAssim    = findViewById(R.id.btnConverterAssim)
        btnTentarDeNovo      = findViewById(R.id.btnTentarDeNovo)
        recyclerView         = findViewById(R.id.recyclerViewParagrafos)
        // Logo depois do recyclerView: o controller da barra de capítulos recebe a lista no construtor.
        capitulosController.configurar()
        recyclerView.limitarLarguraEmTelaAmpla(LARGURA_MAXIMA_LEITURA_DP)
        fabConverterPrincipal = findViewById(R.id.fabConverterPrincipal)
        fabVoltarNarracao = findViewById(R.id.fabVoltarNarracao)
        // Insets, listeners do FAB e da barra guiada e o "Ler sem áudio" (ver ReaderTelaController).
        telaController.configurar()
        bannerOtimizado      = findViewById(R.id.bannerTextoOtimizado)
        btnConcluirModo      = findViewById(R.id.btnConcluirModo)

        tvGuidedSoneca        = findViewById(R.id.tvGuidedSoneca)

        btnBookmark          = findViewById(R.id.btnBookmarkReader)
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun configurarRecyclerView() {
        adapter = ParagrafoAdapter(
            activity = this,
            recyclerView = recyclerView,
            barraSelecaoTexto = findViewById(R.id.barraSelecaoTexto),
            viewModel = viewModel,
            onLongPress = { indice -> viewModel.onParagrafoLongPress(indice) },
            // Sem a guiada, o toque só age na seleção (o viewModel retorna cedo sem seleção ativa, sem áudio).
            onToque     = { indice ->
                guidedReadingBarController?.onParagrafoToque(indice) ?: viewModel.onParagrafoToque(indice)
            },
            onDestacarTrecho = { indice, inicio, fim ->
                marcacoesController.mostrarDialogDestacarTrecho(indice, inicio, fim)
            },
            onDefinirPronuncia = { palavra ->
                vozController.abrirPronunciaDaPalavra(palavra)
            },
            onGerarAudioDoTrecho = { trecho -> retornoController.gerarAudioDoTrecho(trecho) },
            comAudio = !somenteLeitura,
        )
        rolagemController.aplicarOrientacao(viewModel.uiState.value.rolagemHorizontal)
        // O destaque de progresso da leitura guiada repinta o parágrafo atual ~20x/s
        // (notifyItemChanged); o ItemAnimator padrão faz uma animação de troca (cross-fade)
        // a cada repintura, o que pisca o parágrafo em sincronia e disputa o layout com o
        // smoothScrollToPosition automático. Mesmo padrão de DownloadCentralActivity/
        // BuscarLivrosActivity para listas que atualizam a alta frequência.
        recyclerView.itemAnimator = null
        recyclerView.adapter = adapter
        viewModel.registrarAdapter(adapter)
    }

    // ── Botões ────────────────────────────────────────────────────────────────

    private fun configurarBotoes() {
        btnVoltar.setOnClickListener { retornoController.voltar() }

        btnBookmark.setOnClickListener { marcacoesController.alternarBookmarkNaPosicaoAtual() }

        // Botão de mais opções: abre o BottomSheet unificado de ações
        btnOpcoes.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // Pill de status: também abre o BottomSheet de ações
        bannerOtimizado.marcarComoBotao()
        bannerOtimizado.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // FAB de conversão: só com capítulos marcados (ver estadoDosFabs)
        fabConverterPrincipal.setOnClickListener { retornoController.gerarAudioDaSelecao() }
        // Barra de seleção de vários parágrafos (rb-30): gera o trecho marcado, ou só cancela a seleção
        findViewById<View>(R.id.btnSelecaoGerar).setOnClickListener { retornoController.gerarAudioDosParagrafos() }
        findViewById<View>(R.id.btnSelecaoCancelar).setOnClickListener { viewModel.limparSelecao() }
        fabVoltarNarracao.setOnClickListener { guidedReadingBarController?.voltarAoAcompanhamento() }

        // Barra de modo: botão Concluir (mesma saída do modo que o botão voltar)
        btnConcluirModo.setOnClickListener { retornoController.concluirModoEspecial() }

        // Estado de erro (extração falhou): "Tentar de novo" refaz a extração do mesmo arquivo (limpa o erro e mostra o
        // carregando, ver ReaderViewModel.carregarArquivo).
        btnTentarDeNovo.setOnClickListener { viewModel.carregarArquivo(caminhoArquivo) }

        // "Converter assim mesmo" devolve o arquivo ORIGINAL
        // pra MainActivity converter pelo pipeline tolerante — o leitor não tem texto estruturado
        // pra devolver, então só fecha depois de checar conectividade (como os demais caminhos).
        btnConverterAssim.setOnClickListener {
            conversaoController.verificarConectividadeAntesDeConverter {
                retornoController.retornarConvertendoOriginalSemEstrutura()
            }
        }

        // Busca no texto: campo, anterior, próximo e fechar (ver ReaderBuscaController).
        buscaController.configurar()
    }

    /**
     * Teclado: as teclas que rolam o texto contam como rolagem de quem lê, mas só quando a lista de fato rola (no fim
     * do livro, PAGE_DOWN não muda nada e não liga o modo manual). A busca, que tem o foco, fica de fora.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Só as teclas de rolagem vão ao controller: as outras nunca tocam na lista (ver RegraDaRolagemDoLeitor).
        if (event.action != KeyEvent.ACTION_DOWN || currentFocus is EditText ||
            !RegraDaRolagemDoLeitor.teclaDeRolagem(event.keyCode)) return super.dispatchKeyEvent(event)
        return rolagemController.tratarTecla(event) { super.dispatchKeyEvent(it) }
    }

    /**
     * Visibilidade e conteúdo dos FABs. A regra está em [estadoDosFabs] (TelaDoLeitor.kt); aqui só se aplica.
     * Sem manager ("Ler sem áudio") ela não mexe em nada. A barra guiada só é tocada com a guiada ativa.
     */
    private fun atualizarFabs() {
        val manager = guidedPlayerManager
        val state = viewModel.uiState.value
        val guiadaAtiva = manager != null && manager.state.value.index >= 0
        val fabs = estadoDosFabs(
            temManager = manager != null, guiadaAtiva = guiadaAtiva,
            scrollManual = guiadaAtiva && guidedReadingBarController?.scrollManual == true,
            temSelecao = state.selecaoInicio != null, capitulosMarcados = capitulosSelecionados.size,
        ) ?: return
        telaController.aplicarFabs(fabs)
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

    private fun atualizarUI(state: ReaderUiState) {
        // A fila já narra outro livro: esta tela não pode entregar o texto dela ao manager (setParagraphs).
        if (guiadaEmOutroLivro()) {
            guidedPlayerManager?.let(::abrirLeitorDoLivroDaFila)
            return
        }

        // Carregando, erro, pílula e modo: o cálculo é puro (TelaDoLeitor.kt); o controller só aplica.
        val tela = calcularTelaDoLeitor(state, telaController.pilulaDaLimpezaMostrada)
        val texto = if (telaController.aplicarCarregandoEErro(tela)) null else viewModel.textoEfetivo()
        if (texto == null) return

        processarAutoStartLeituraGuiada(texto)

        // Mostra conteúdo
        recyclerView.visibility = View.VISIBLE

        capitulosController.atualizarBarra(texto)

        // ── Pill de status (banner de limpeza) e barra de modo ativo ───────────
        telaController.aplicarStatus(tela, texto)

        // ── Modo do adapter ────────────────────────────────────────────────────
        val paragrafosExibidos = atualizarAdapterDeLeitura(state, texto, tela.modoAdapter)

        // ── Busca ──────────────────────────────────────────────────────────────
        buscaController.aplicar(state)

        // Restaura a posição de leitura salva — apenas uma vez, no carregamento inicial,
        // e nunca quando a leitura guiada vai iniciar sozinha (ela faz seu próprio scroll).
        rolagemController.restaurarPosicaoSeNecessario(paragrafosExibidos)
        executarAcaoInicialSeNecessario()
        // Uma dica por abertura: primeiro o menu ⋮, na seguinte a barra de capítulos.
        telaController.tentarDica { capitulosController.painelAberto }

        // ── FABs ──────────────────────────────────────────────────────────────
        atualizarFabs()

        // Scroll sync automático (só se não foi scroll manual). Só rola quando o
        // parágrafo muda — atualizarUI roda a cada tick de progresso (20fps) e
        // re-rolar para o mesmo índice causava flicker (#3).
        rolagemController.sincronizarComAudiobook(state)

        // Tema do fundo e margem horizontal da lista de parágrafos.
        telaController.aplicarTemaEMargem(state)

        capitulosController.atualizarProgressoLeitura()
    }

    /** Extraído de [atualizarUI] (ações disparadas pelas intents de leitura guiada). */
    private fun processarAutoStartLeituraGuiada(texto: TextoExtraido) {
        // "Ler sem áudio" nunca inicia a guiada (nem o diálogo de início nem a leitura direta).
        if (somenteLeitura) return
        val controlador = guidedReadingBarController ?: return
        // A regra (espera a limpeza, reconexão, uma vez só) está em decidirInicioDaGuiada, em TelaDoLeitor.kt.
        val estado = viewModel.uiState.value
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = somenteLeitura, estado = estado,
            pedidos = InicioDaGuiada(
                abrirDialogo = intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false),
                continuar = intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false),
            ),
            jaResolvidos = InicioDaGuiada(autoStartGuidedTriggered, continuarGuiadaTriggered),
            reconectando = reconectandoGuided,
        )
        if (decisao.abrirDialogo) {
            autoStartGuidedTriggered = true
            controlador.mostrarDialogPontoInicioLeituraGuiada()
        }

        // "Continuar lendo": inicia a guiada direto na posição salva (sem diálogo).
        if (decisao.continuar) {
            continuarGuiadaTriggered = true
            val salvo = appPrefs.posicaoLeitura(caminhoArquivo, -1)
            // Primeiro livro da fila "Ler tudo": a mesma regra dos seguintes (um livro já lido até o fim recomeça).
            val inicio = if (intent.hasExtra(EXTRA_FILA_SEQUENCIA)) {
                ReadingStartDetector.indiceParaFilaSequencia(salvo, texto.paragrafos.size, texto.capitulos)
            } else {
                ReadingStartDetector.indiceParaContinuar(salvo, texto.paragrafos.size, texto.capitulos)
            }
            recyclerView.post { controlador.playGuidedFrom(inicio) }
        }
    }

    /**
     * A guiada (fila "Ler tudo") narra outro livro: a tela deste livro cede o lugar ao livro que está sendo narrado.
     */
    private fun guiadaEmOutroLivro(): Boolean {
        val caminhoDaGuiada = guidedPlayerManager?.caminhoArquivo ?: return false
        return caminhoDaGuiada.isNotEmpty() && caminhoDaGuiada != caminhoArquivo
    }

    /**
     * Fecha esta tela e abre o livro que o manager está narrando, com o restante da fila. A nova tela reconecta ao
     * manager vivo (mesmo documento e index >= 0), então não recomeça a leitura: só mostra o livro atual.
     * Chamado só com a tela visível ou ao voltar a ela, nunca em segundo plano (o Android bloqueia abrir tela de lá).
     */
    private fun abrirLeitorDoLivroDaFila(manager: GuidedPlayerManager) {
        if (livroTrocadoPelaFila || isFinishing) return
        livroTrocadoPelaFila = true
        val proximaIntent = Intent(this, ReaderActivity::class.java)
            .putExtra(EXTRA_CAMINHO, manager.caminhoArquivo)
            .putStringArrayListExtra(EXTRA_FILA_SEQUENCIA, ArrayList(manager.filaSequencia))
        // BUG REAL (relatado pelo usuário): ReaderActivity é `launchMode="singleTop"` — como
        // esta Activity É a instância no topo da pilha, startActivity() ANTES de finish()
        // fazia o Android tratar como "reentrega de intent" (onNewIntent, não implementado
        // aqui) em vez de abrir o próximo livro. finish() PRIMEIRO marca esta instância como
        // finalizando antes do startActivity() seguinte resolver — o sistema já não a considera
        // "topo da pilha" e cria uma instância nova de verdade (com onCreate rodando do zero
        // pro novo caminho), como esperado.
        finish()
        startActivity(proximaIntent)
    }

    /** Extraído de [atualizarUI] (modo do adapter + atualização). O modo vem do cálculo da tela ([modoAdapter]). */
    private fun atualizarAdapterDeLeitura(
        state: ReaderUiState, texto: TextoExtraido, modoAdapter: AdapterModo,
    ): List<Paragrafo> {
        // Atualiza adapter
        val paragrafosExibidos = state.textoLimpo?.paragrafos ?: texto.paragrafos
        if (ultimoTextoExibido !== paragrafosExibidos) {
            ultimoTextoExibido = paragrafosExibidos
            guidedPlayerManager?.setParagraphs(paragrafosExibidos)
            // Análise de falantes serve às vozes (TTS): não roda no modo "Ler sem áudio".
            if (!somenteLeitura) personagensController.prepararAtribuicaoDeFalantes(paragrafosExibidos)
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
            tipografia          = state.tipografia,
            alinhamento         = state.alinhamento
        )
        return paragrafosExibidos
    }

    /** Abre o painel pedido pela tela do livro (uma vez; o extra é consumido para não repetir na rotação). */
    private fun executarAcaoInicialSeNecessario() {
        val acao = intent.getStringExtra(EXTRA_ACAO_INICIAL) ?: return
        intent.removeExtra(EXTRA_ACAO_INICIAL)
        when (acao) {
            ACAO_INICIAL_CAPITULOS -> capitulosController.mostrarPainel()
            ACAO_INICIAL_PERSONAGENS -> personagensController.abrirConfiguracaoVozesPersonagens()
            ACAO_INICIAL_PRONUNCIA -> vozController.abrirDicionarioPronuncia()
        }
    }

    // Clonar pela lista de vozes do leitor: a voz nova já fica escolhida.
    // Inicializador de campo: registra lançadores no construtor, por isso não vai para lazy (só delega por lambda).
    private val cloneVoiceFlow = CloneVoiceFlow(this, aoCriarVoz = { id ->
        VoiceCatalog.findAny(id)?.let { vozController.selecionarVoz(it) }
    })

    // Clonar pelo painel "Voz e velocidade" deste livro: a voz nova vira a voz do livro, pela mesma rotina da
    // lista do painel (ConfigLivroDialog). O painel entrega a sua escolha em [aoClonarVozDoPainel] antes de clonar.
    private var aoClonarVozDoPainel: ((VoiceOption) -> Unit)? = null
    private val cloneVoiceFlowDoLivro = CloneVoiceFlow(this, aoCriarVoz = { id ->
        // Usa a escolha uma vez só: não segura o painel depois do clone.
        val aoEscolher = aoClonarVozDoPainel
        aoClonarVozDoPainel = null
        VoiceCatalog.findAny(id)?.let { voz -> aoEscolher?.invoke(voz) }
    })

    // Inicializador de campo (registra o listener do seletor de vozes no construtor): só delega para o vozController.
    private val voiceSelectionDelegate: VoiceSelectionDelegate = VoiceSelectionDelegate(
        activity = this,
        onVoiceSelected = { vozController.selecionarVoz(it) },
        onVoicePreview = { voice, onFinished -> vozController.ouvirAmostra(voice, onFinished) },
        obterVozAtualId = { appPrefs.vozSelecionada.orEmpty() },
        onCloneVoiceRequested = { cloneVoiceFlow.iniciar() },
    )
}
