package com.jonjonesbr.audiobookgen.ui

import android.net.Uri
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.domain.ReadingStartDetector
import com.jonjonesbr.audiobookgen.domain.RegraDeReconexaoGuiada
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import com.jonjonesbr.audiobookgen.domain.ChapterExportPolicy
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.MARGEM_NIVEL_PADRAO
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import com.jonjonesbr.audiobookgen.util.aplicarMargemInferiorComNavigationBar
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import com.jonjonesbr.audiobookgen.util.dpToPx
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import com.jonjonesbr.audiobookgen.util.expandirEmPaisagem
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ReaderActivity : BasePlayerActivity() {

    companion object {
        private const val PAUSA_ROLAGEM_MANUAL_MS = 8_000L
        /** Respiro entre o botão "Voltar à narração" e a barra da leitura guiada (que fica na base da tela). */
        private const val MARGEM_FAB_VOLTAR_DP = 16
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
        private const val ALFA_SETA_DESABILITADA = 0.3f
        private const val CAPITULOS_ACIMA_DO_ATUAL = 2
        private const val MIN_CAPITULOS_PARA_BUSCA = 12
        /** O resultado da busca fica a 1/[FRACAO_MARGEM_BUSCA] da altura, contando do topo da lista. */
        private const val FRACAO_MARGEM_BUSCA = 4
        /** Marca de "fim do livro na tela" para não refazer o texto do progresso a cada rolagem parada. */
        private const val PROGRESSO_CHAVE_FIM = -2
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
         *  (QueueActivity). Quem avança é o GuidedPlayerManager: ele guarda a fila e troca de
         *  livro sozinho, inclusive com a tela apagada. Esta Activity só segue o livro que está
         *  sendo narrado (ver abrirLeitorDoLivroDaFila). */
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
        private const val DURACAO_PILL_LIMPEZA_MS = 5000L
        private const val PARAGRAFOS_PREVIEW = 3
        private const val PREPARO_PARAGRAFOS_SEM_CAPITULO = 120
        private const val LARGURA_MAXIMA_LEITURA_DP = 720
        // Margem horizontal da lista de parágrafos por nível (0=estreita, 1=média, 2=larga).
        val MARGEM_NIVEL_DP = intArrayOf(8, 20, 36)
    }

    private val viewModel: ReaderViewModel by viewModels()
    private val audiobookViewModel: AudiobookViewModel by viewModels()

    // Views
    private lateinit var btnVoltar: ImageButton
    private lateinit var tvTitulo: TextView
    private lateinit var btnOpcoes: ImageButton
    private var dicaLeitorTentada = false
    private lateinit var barraCapitulo: View
    private lateinit var tvCapituloAtual: TextView
    private lateinit var btnCapituloAnterior: android.widget.ImageButton
    private lateinit var btnCapituloProximo: android.widget.ImageButton
    private lateinit var tvProgressoLeitura: TextView
    private lateinit var layoutCarregando: View
    private lateinit var tvCarregando: TextView
    /** O resultado da limpeza aparece uma vez por abertura do leitor. */
    private var pillDaLimpezaMostrada = false
    private lateinit var layoutErro: LinearLayout
    private lateinit var tvErro: TextView
    private lateinit var btnConverterAssim: Button
    private lateinit var recyclerView: RecyclerViewParagrafos
    private var scrollListener: RecyclerView.OnScrollListener? = null
    // FAB único de conversão: texto/ícone mudam conforme há seleção ativa ou não
    // (antes eram dois FABs separados, fabConverterPrincipal e fabConverterSelecao,
    // sempre mutuamente exclusivos — unificados num só, ver atualizarFabs()).
    private lateinit var fabConverterPrincipal: ExtendedFloatingActionButton
    // "Voltar à narração": no lugar do FAB de conversão, só no modo manual da guiada (ver atualizarFabs()).
    private lateinit var fabVoltarNarracao: ExtendedFloatingActionButton

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
    private var ultimaBuscaRolada: List<Int> = emptyList()

    private lateinit var adapter: ParagrafoAdapter
    private var caminhoArquivo: String = ""
    private var arquivoTrechoTemp: File? = null
    private var preservarArquivoTrechoTemp = false
    private var dialogAtivo: androidx.appcompat.app.AlertDialog? = null
    private val dialogos = DialogosAbertos(this)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)
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
            mostrarErro(R.string.toast_clean_text_export_error)
        }
    }

    private val exportarCapitulosPasta = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) exportarCapitulosPara(uri) }

    /** Nulo no modo "Ler sem áudio" (o manager nunca é criado). Todo acesso trata o nulo. */
    private var guidedPlayerManager: GuidedPlayerManager? = null
    private val somenteLeitura: Boolean by lazy { intent.getBooleanExtra(EXTRA_SOMENTE_LEITURA, false) }
    private var ultimoTextoExibido: List<Paragrafo>? = null
    private var autoStartGuidedTriggered = false
    private var scrollRestaurado = false
    private var reconectandoGuided = false
    private var margemNivelAplicado = -1
    private var temaBarrasAplicado: TemaLeitura? = null
    private var progressoIndiceAplicado = -1
    private var continuarGuiadaTriggered = false
    // A fila "Ler tudo" já abriu o livro seguinte nesta tela: ela está fechando e não grava mais nada.
    private var livroTrocadoPelaFila = false
    private val appPrefs by lazy { com.jonjonesbr.audiobookgen.data.AppPrefs(this) }
    /** Capítulo da posição de leitura mostrado na barra (-1 = ainda não calculado). */
    private var capituloNaBarra = -1
    private var textoDaBarra: String? = null

    private val aparenciaController by lazy {
        ReaderAparenciaController(this, viewModel) { horizontal ->
            aplicarOrientacaoRolagem(horizontal)
        }
    }

    /** Nulo no modo "Ler sem áudio" (não há manager para a barra conectar). Não acessar antes de o manager ser atribuído no onCreate. */
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
                    // A barra da leitura guiada e o mini player do audiobook não aparecem juntos (ver ocultarMiniPlayerAudio).
                    mostrarMiniPlayer = { reavaliarMiniPlayer() },
                    esconderMiniPlayer = { reavaliarMiniPlayer() },
                    obterCaminhoArquivo = { caminhoArquivo },
                    aoMudarDeLivroNaFila = { guidedPlayerManager?.let(::abrirLeitorDoLivroDaFila) },
                    prepararCapitulo = { prepararCapituloAtual() },
                    aoEncerrarGuiada = { paragrafoGuiadoAoEncerrar = it },
                ),
            )
        }
    }
    private val marcacoesController by lazy {
        ReaderMarcacoesController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderMarcacoesController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterParagrafoAtual = { paragrafoAtualVisivel() },
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

    private val sonecaController by lazy {
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
                onAtualizarSoneca = { ms ->
                    tvGuidedSoneca.text =
                        if (ms != null) "😴 ${SleepTimerManager.formatRemaining(ms)}" else "😴"
                    tvGuidedSoneca.visibility = View.VISIBLE
                },
            ),
        )
    }

    private val personagensController by lazy {
        ReaderPersonagensController(
            activity = this,
            viewModel = viewModel,
            callbacks = ReaderPersonagensController.Callbacks(
                obterCaminhoArquivo = { caminhoArquivo },
                obterGuidedPlayerManager = { guidedPlayerManager },
                ouvirPronuncia = ::ouvirPronuncia,
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
                onCompartilharTrecho = { compartilharTrechoAtual() },
                onBaixarCapitulo = { baixarCapitulo() },
                onBaixarLivro = { gerarLivroInteiro() },
                onGerarCapitulosMarcados = {
                    verificarConectividadeAntesDeConverter { retornoController.retornarComSelecao() }
                },
                onOuvirAudiobook = { ouvirAudiobookDesteLivro() },
                onConfigurarVozesPersonagens = { personagensController.abrirConfiguracaoVozesPersonagens() },
                onAbrirMarcacoes = { marcacoesController.mostrarDialogListarMarcacoes() },
                onAbrirPronuncia = { abrirDicionarioPronuncia() },
                onAbrirAparencia = { aparenciaController.mostrarBottomSheet() },
                onAbrirSleepTimer = { sonecaController.mostrarDialogoSleepTimer() },
                onClonarVoz = { cloneVoiceFlow.iniciar() },
                onPerfilDoLivroMudou = {
                    guidedPlayerManager?.recarregarPerfilDoLivro()
                    guidedReadingBarController?.atualizarRotulosDoPerfil()
                },
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
        // Tela recriada (giro, tema) de um livro que a fila já deixou para trás: vai para o livro que está sendo narrado,
        // antes de criar um manager para o livro velho. Sem isto, o manager da sessão ganharia os parágrafos do livro velho.
        // Só a tela que estava ligada à sessão volta para ela: um livro aberto de propósito pela biblioteca fica.
        if (savedInstanceState != null && !somenteLeitura) {
            val sessao = GuidedPlaybackBridge.manager
            if (sessao != null && savedInstanceState.getLong(STATE_TOKEN_SESSAO_GUIADA, -1L) == sessao.sessionToken &&
                RegraDeReconexaoGuiada.leitorFicouParaTras(
                    caminhoArquivo, sessao.caminhoArquivo, sessao.state.value.index, sessao.livrosDeixadosNaFila
                )
            ) {
                abrirLeitorDoLivroDaFila(sessao)
                return
            }
        }
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
            // Fila "Ler tudo": a intent traz os próximos livros. Girando com a guiada tocando, vale a fila que o manager
            // já andou (a da intent original está desatualizada); abrindo de novo pela fila, vale a da intent.
            val filaDaIntent = intent.getStringArrayListExtra(EXTRA_FILA_SEQUENCIA)
            if (filaDaIntent != null && (savedInstanceState == null || !reconectandoGuided)) {
                manager.filaSequencia = filaDaIntent.toList()
            }
        }

        vincularViews()
        configurarRecyclerView()
        configurarBotoes()
        guidedReadingBarController?.configurarGuidedPlayerBotoes()
        configurarScrollListener()
        onBackPressedDispatcher.addCallback(this) { retornoController.voltar() }

        tvTitulo.text = tituloCompleto
        tvTitulo.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(null)
                .setMessage(tituloCompleto)
                .setPositiveButton(R.string.dialog_ok, null)
                .exibir()
            true
        }

        viewModel.carregarPreferencias(appPrefs)
        // configurarRecyclerView() já rodou com o default (vertical) — carregarPreferencias()
        // só agora carrega o valor salvo, então reaplica a orientação correta.
        aplicarOrientacaoRolagem(viewModel.uiState.value.rolagemHorizontal)

        // Bug #4 fix: sincroniza motor TTS, chave e pausas para o GuidedPlayerManager
        val motorInit = appPrefs.motorTts
        val chaveInit = SecurePreferences.getGeminiKeys(this)
        val pausaMsInit = appPrefs.pausaMs
        lifecycleScope.launch {
            pythonUseCase.sincronizarConfiguracoes(motorInit, chaveInit, pausaMsInit, caminhoDoLivro = caminhoArquivo)
        }

        observarViewModel()
        guidedReadingBarController?.observarGuidedPlayer()
        if (!somenteLeitura) sonecaController.observarSleepTimer()
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
        dialogAtivo?.dismiss()
        dialogAtivo = null
        viewModel.salvarPreferencias(appPrefs)
        // Salva a posição de leitura para retomar depois: o parágrafo da leitura guiada, ativa ou recém-encerrada sem
        // rolagem manual depois (o marcador fica abaixo do topo da tela); senão o primeiro parágrafo visível.
        // Livro já trocado pela fila: o manager gravou a posição final do livro concluído; esta tela não grava por cima.
        if (!livroTrocadoPelaFila && ::recyclerView.isInitialized && caminhoArquivo.isNotEmpty()) {
            val guiadaEncerrada = paragrafoGuiadoAoEncerrar
                .takeIf { it >= 0 && viewModel.uiState.value.paragrafoSyncAtual < 0 }
            // Fim do livro na tela: grava o último parágrafo, para a biblioteca mostrar o livro como lido (100%).
            val fim = if (viewModel.uiState.value.paragrafoSyncAtual < 0 && fimDoLivroNaTela()) {
                adapter.itemCount - 1 // fimDoLivroNaTela() já garante o adapter inicializado e com itens
            } else {
                null
            }
            (guiadaEncerrada ?: fim ?: paragrafoAtualVisivel())
                ?.let {
                    // Total de parágrafos: dá a porcentagem lida (progresso = scroll / total) na biblioteca.
                    appPrefs.salvarPosicaoLeitura(caminhoArquivo, it, if (::adapter.isInitialized) adapter.itemCount else 0)
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scrollListener?.let { recyclerView.removeOnScrollListener(it) }
        scrollListener = null
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
        barraCapitulo        = findViewById(R.id.barraCapitulo)
        tvCapituloAtual      = findViewById(R.id.tvCapituloAtual)
        btnCapituloAnterior  = findViewById(R.id.btnCapituloAnterior)
        btnCapituloProximo   = findViewById(R.id.btnCapituloProximo)
        btnCapituloAnterior.setOnClickListener { irParaCapitulo(capituloNaBarra - 1) }
        btnCapituloProximo.setOnClickListener { irParaCapitulo(capituloNaBarra + 1) }
        findViewById<View>(R.id.areaCapituloAtual).setOnClickListener { mostrarBottomSheetCapitulos() }
        tvProgressoLeitura   = findViewById(R.id.tvProgressoLeitura)
        layoutCarregando     = findViewById(R.id.layoutCarregandoReader)
        tvCarregando         = findViewById(R.id.tvCarregandoReader)
        layoutErro           = findViewById(R.id.layoutReaderErro)
        tvErro               = findViewById(R.id.tvReaderErro)
        btnConverterAssim    = findViewById(R.id.btnConverterAssim)
        recyclerView         = findViewById(R.id.recyclerViewParagrafos)
        recyclerView.limitarLarguraEmTelaAmpla(LARGURA_MAXIMA_LEITURA_DP)
        fabConverterPrincipal = findViewById(R.id.fabConverterPrincipal)
        // T6.1 (edge-to-edge): FAB e barra de leitura guiada são ancorados na base da tela —
        // sem isso, ficam atrás da barra de gestos em telas sem botões de navegação físicos.
        fabConverterPrincipal.aplicarMargemInferiorComNavigationBar()
        fabVoltarNarracao = findViewById(R.id.fabVoltarNarracao)
        findViewById<View>(R.id.layoutGuidedPlayer).aplicarPaddingInferiorComNavigationBar()
        // A barra guiada muda de altura (chips, modo manual): o "Voltar à narração" acompanha a borda de cima dela.
        findViewById<View>(R.id.layoutGuidedPlayer).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            posicionarFabVoltarNarracao()
        }
        EspacoSobControles.manter(
            recyclerView, listOf(findViewById(R.id.layoutGuidedPlayer), findViewById(R.id.miniPlayerRoot))
        )
        if (somenteLeitura) {
            // "Ler sem áudio": sem FAB de conversão, sem barra da leitura guiada e sem "converter assim mesmo".
            fabConverterPrincipal.visibility = View.GONE
            findViewById<View>(R.id.layoutGuidedPlayer).visibility = View.GONE
            btnConverterAssim.visibility = View.GONE
        }
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
            // Sem a guiada, o toque só age na seleção (o viewModel retorna cedo sem seleção ativa, sem áudio).
            onToque     = { indice ->
                guidedReadingBarController?.onParagrafoToque(indice) ?: viewModel.onParagrafoToque(indice)
            },
            onDestacarTrecho = { indice, inicio, fim ->
                marcacoesController.mostrarDialogDestacarTrecho(indice, inicio, fim)
            },
            onDefinirPronuncia = { palavra ->
                PronunciationDialog.show(this, palavra, caminhoDoLivro = caminhoArquivo, onOuvir = ::ouvirPronuncia)
            },
            mostrarPronuncia = !somenteLeitura,
        )
        aplicarOrientacaoRolagem(viewModel.uiState.value.rolagemHorizontal)
        // O destaque de progresso da leitura guiada repinta o parágrafo atual ~20x/s
        // (notifyItemChanged); o ItemAnimator padrão faz uma animação de troca (cross-fade)
        // a cada repintura, o que pisca o parágrafo em sincronia e disputa o layout com o
        // smoothScrollToPosition automático. Mesmo padrão de DownloadCentralActivity/
        // BuscarLivrosActivity para listas que atualizam a alta frequência.
        recyclerView.itemAnimator = null
        recyclerView.adapter = adapter
        viewModel.registrarAdapter(adapter)
    }

    private var pagerSnapHelper: androidx.recyclerview.widget.PagerSnapHelper? = null

    /** Alterna o RecyclerView entre scroll vertical contínuo (padrão) e horizontal paginado
     * (com PagerSnapHelper — "página" por página é o mesmo parágrafo, unidade de navegação
     * inalterada). Chamada tanto na criação quanto ao alternar a opção ao vivo. */
    private fun aplicarOrientacaoRolagem(horizontal: Boolean) {
        pagerSnapHelper?.attachToRecyclerView(null)
        recyclerView.layoutManager = LeitorLayoutManager(
            this,
            if (horizontal) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        ) { leituraGuiadaConduzRolagem() }
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
        btnVoltar.setOnClickListener { retornoController.voltar() }

        findViewById<android.widget.ImageButton>(R.id.btnBuscarReader)
            .setOnClickListener { alternarBusca() }

        btnBookmark.setOnClickListener { marcacoesController.alternarBookmarkNaPosicaoAtual() }

        // Botão de mais opções: abre o BottomSheet unificado de ações
        btnOpcoes.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // Pill de status: também abre o BottomSheet de ações
        bannerOtimizado.marcarComoBotao()
        bannerOtimizado.setOnClickListener { retornoController.mostrarBottomSheetAcoes() }

        // FAB de conversão: converter livro inteiro OU seleção, dependendo do estado atual
        fabConverterPrincipal.setOnClickListener {
            verificarConectividadeAntesDeConverter { retornoController.retornarComSelecao() }
        }
        fabVoltarNarracao.setOnClickListener { guidedReadingBarController?.voltarAoAcompanhamento() }

        // Barra de modo: botão Concluir (mesma saída do modo que o botão voltar)
        btnConcluirModo.setOnClickListener { retornoController.concluirModoEspecial() }

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
                val naTela = (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                viewModel.buscarTexto(s?.toString() ?: "", naTela)
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
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        if (posicao != null && lm != null) {
            // Salto direto (o smoothScroll era lento em livros longos e deixava o parágrafo achado
            // colado na borda, às vezes atrás da barra de busca). Fica a ~1/4 da altura da tela.
            val vertical = lm.orientation == LinearLayoutManager.VERTICAL
            val margem = if (vertical) recyclerView.height / FRACAO_MARGEM_BUSCA else 0
            lm.scrollToPositionWithOffset(posicao, margem)
        }
    }

    private fun configurarScrollListener() {
        scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Só o arraste com o dedo chega em DRAGGING: a rolagem automática do código não entra nesse estado.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    rolagemPeloLeitor()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    guidedReadingBarController?.aguardandoAutoScroll = false
                    atualizarProgressoLeitura()
                }
            }
        }
        recyclerView.addOnScrollListener(scrollListener!!)
        // As outras entradas de quem lê: TalkBack (ações de rolagem da lista), roda do mouse/touchpad e teclado.
        recyclerView.aoRolagemDeAcessibilidade = { rolagemPeloLeitor() }
        recyclerView.setOnGenericMotionListener { _, evento ->
            if (evento.action == MotionEvent.ACTION_SCROLL) rolagemPeloLeitor()
            false // o RecyclerView continua tratando a roda normalmente
        }
    }

    /**
     * Rolagem iniciada por quem lê, qualquer que seja a entrada (dedo, TalkBack, teclado ou roda). Liga o modo manual
     * da leitura guiada, que só se desliga com um pedido explícito (ver GuidedReadingBarController).
     */
    private fun rolagemPeloLeitor() {
        guidedReadingBarController?.ligarRolagemManual()
        // Rolou por conta própria: a posição a guardar volta a ser a da tela, não a da leitura guiada encerrada.
        paragrafoGuiadoAoEncerrar = -1
        // Rolar suspende a rolagem automática do audiobook por um tempo; antes desligava o acompanhamento de vez
        // (o destaque parava até reabrir o leitor).
        rolagemAudiobookSuspensaAte = android.os.SystemClock.uptimeMillis() + PAUSA_ROLAGEM_MANUAL_MS
    }

    /**
     * Teclado: as teclas que rolam o texto contam como rolagem de quem lê, mas só quando a lista de fato rola (no fim
     * do livro, PAGE_DOWN não muda nada e não liga o modo manual). A busca, que tem o foco, fica de fora.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || currentFocus is EditText) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            // O RecyclerView não trata as teclas de página: a rolagem é feita aqui, uma tela por vez.
            KeyEvent.KEYCODE_PAGE_UP -> if (rolarPagina(-1)) return true
            KeyEvent.KEYCODE_PAGE_DOWN -> if (rolarPagina(1)) return true
            // Setas: a lista só rola se o foco for para um filho fora da tela. Conta só se o deslocamento mudou.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val antes = deslocamentoAtual()
                val tratada = super.dispatchKeyEvent(event)
                if (deslocamentoAtual() != antes) rolagemPeloLeitor()
                return tratada
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Rola uma tela para trás (-1) ou para frente (1), no eixo da lista. Falso se a lista já está no limite. */
    private fun rolarPagina(sentido: Int): Boolean {
        val vertical = recyclerView.layoutManager?.canScrollVertically() == true
        val podeRolar = if (vertical) {
            recyclerView.canScrollVertically(sentido)
        } else {
            recyclerView.canScrollHorizontally(sentido)
        }
        if (!podeRolar) return false
        if (vertical) {
            recyclerView.smoothScrollBy(0, sentido * recyclerView.height)
        } else {
            recyclerView.smoothScrollBy(sentido * recyclerView.width, 0)
        }
        rolagemPeloLeitor()
        return true
    }

    /** Deslocamento da lista no eixo atual, para saber se uma tecla mexeu nela. */
    private fun deslocamentoAtual(): Int =
        if (recyclerView.layoutManager?.canScrollVertically() == true) {
            recyclerView.computeVerticalScrollOffset()
        } else {
            recyclerView.computeHorizontalScrollOffset()
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
        // Sem manager (modo "Ler sem áudio") o FAB de conversão fica escondido em vincularViews().
        val manager = guidedPlayerManager ?: return
        val state = viewModel.uiState.value
        val temCapitulos = capitulosSelecionados.isNotEmpty()
        val temSelecaoAtiva = state.selecaoInicio != null || temCapitulos
        val guiadaAtiva = manager.state.value.index >= 0

        // Modo manual da guiada (quem lê rolou): o "Voltar à narração" ocupa o lugar do FAB, sem sobrepor a barra.
        fabVoltarNarracao.visibility = if (guiadaAtiva && guidedReadingBarController?.scrollManual == true) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Só com trecho ou capítulos marcados: o livro inteiro e o capítulo atual ficam em ⋮ › Gerar audiobook
        // (antes o botão ficava sempre sobre o texto, com outro nome, "Converter para áudio").
        fabConverterPrincipal.visibility = if (temSelecaoAtiva && !guiadaAtiva) View.VISIBLE else View.GONE
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
    }

    /**
     * Mantém o "Voltar à narração" logo acima da barra da leitura guiada. O chip só aparece com a barra visível,
     * então a folga vale sempre; a altura da barra já inclui a barra de gestos.
     */
    private fun posicionarFabVoltarNarracao() {
        val barra = findViewById<View>(R.id.layoutGuidedPlayer)
        val alvo = (if (barra.visibility == View.VISIBLE) barra.height else 0) + dpToPx(MARGEM_FAB_VOLTAR_DP)
        val params = fabVoltarNarracao.layoutParams as ViewGroup.MarginLayoutParams
        // Só mexe quando muda. Este método roda dentro do layout da barra: o requestLayout vai para o próximo
        // ciclo, senão o ViewRootImpl adia a margem para outro frame (e pode avisar "requestLayout during layout").
        if (params.bottomMargin != alvo) {
            params.bottomMargin = alvo
            fabVoltarNarracao.post { fabVoltarNarracao.requestLayout() }
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
                text = getString(R.string.reader_progresso_iniciando)
                textSize = DIALOG_PROGRESSO_TEXT_SIZE_SP
                setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
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
            dialogProgressoConversao?.let { dialogos.mostrar(it) }
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

    /** Último parágrafo lido pela leitura guiada encerrada nesta tela (-1: nenhum ou o usuário rolou depois). */
    private var paragrafoGuiadoAoEncerrar = -1

    /** Última geração de audiobook pedida aqui (capítulo ou livro), para o "Tentar de novo" do aviso de erro. */
    private var refazerConversao: (() -> Unit)? = null

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
                                // Aviso com ação "Abrir": toca o audiobook pronto, como o "Ouvir" das conversões.
                                com.google.android.material.snackbar.Snackbar.make(
                                    findViewById(android.R.id.content),
                                    R.string.toast_download_completed,
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                ).setAction(R.string.dl_abrir_livro) {
                                    abrirAudiobookGerado(evento.caminho)
                                }.show()
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
                                // "Tentar de novo" refaz a mesma geração (capítulo ou livro) a partir do texto atual.
                                mostrarErro(msg, refazerConversao?.let { getString(R.string.acao_tentar_de_novo) to it })
                            }
                            is ConversionDoneEvent.Pausada -> {
                                Toast.makeText(
                                    this@ReaderActivity,
                                    R.string.status_conversion_paused,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun baixarCapitulo() {
        val texto = viewModel.textoEfetivo() ?: return
        refazerConversao = ::baixarCapitulo
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
        val speakerMapPath = personagensController.caminhoMapaDePersonagens(textosDoLivro)
        val arquivoTemp = File(cacheDir, "download_chapter_${System.currentTimeMillis()}.txt")
        try {
            arquivoTemp.writeText(trechoTexto, Charsets.UTF_8)
            iniciarDownloadBackground(arquivoTemp.absolutePath, nomeCapitulo, speakerMapPath = speakerMapPath, caminhoDoLivro = caminhoArquivo)
        } catch (e: Exception) {
            Log.w("ReaderActivity", "Erro ao preparar download de capítulo", e)
            mostrarErro(getString(R.string.toast_erro_preparar_download, getString(mensagemDeErro(e))))
        }
    }

    /**
     * "Gerar audiobook › Livro inteiro": abre o painel da tela do livro (voz, ritmo, tempo estimado, pasta, fila).
     * Com o texto editado ou limpo aqui no leitor, ou um arquivo fora da biblioteca, gera direto deste texto.
     */
    private fun gerarLivroInteiro() {
        val state = viewModel.uiState.value
        lifecycleScope.launch {
            val livro = if (state.temEdicoes || state.textoLimpo != null) {
                null
            } else {
                com.jonjonesbr.audiobookgen.data.BibliotecaStore.porCaminho(applicationContext, caminhoArquivo)
            }
            if (livro == null) {
                baixarLivroCompleto()
            } else {
                startActivity(
                    Intent(this@ReaderActivity, LivroActivity::class.java)
                        .putExtra(LivroActivity.EXTRA_ID, livro.id)
                        .putExtra(LivroActivity.EXTRA_ABRIR_PAINEL_GERAR, true)
                        .putExtra(LivroActivity.EXTRA_SO_PAINEL, true)
                )
            }
        }
    }

    private fun baixarLivroCompleto() {
        val texto = viewModel.textoEfetivo() ?: return
        refazerConversao = ::baixarLivroCompleto
        val nomeSemExt = File(caminhoArquivo).nameWithoutExtension
        lifecycleScope.launch {
            personagensController.aguardarAnaliseDeFalantes()
            val trechoTexto = texto.paragrafos.joinToString("\n\n") { it.texto }
            val textosDoLivro = texto.paragrafos.map { it.texto }
            val speakerMapPath = personagensController.caminhoMapaDePersonagens(textosDoLivro)
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
                mostrarErro(getString(R.string.toast_erro_preparar_download, getString(mensagemDeErro(e))))
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
                val total = resultado.getOrDefault(0)
                resources.getQuantityString(R.plurals.toast_capitulos_exportados_qtd, total, total)
            } else {
                getString(
                    R.string.toast_erro_exportar_capitulos,
                    resultado.exceptionOrNull()?.let { getString(mensagemDeErro(it)) }
                        ?: getString(R.string.erro_amigavel_generico)
                )
            }
            if (resultado.isSuccess) {
                Toast.makeText(this@ReaderActivity, mensagem, Toast.LENGTH_LONG).show()
            } else {
                mostrarErro(mensagem)
            }
        }
    }


    private fun iniciarDownloadBackground(
        caminhoTexto: String,
        nomeAudiobook: String,
        livroOrigemCaminho: String? = null,
        speakerMapPath: String? = null,
        caminhoDoLivro: String? = livroOrigemCaminho
    ) {
        val iniciou = audiobookViewModel.iniciarProcessamentoDeTexto(
            caminhoTexto,
            nomeAudiobook,
            livroOrigemCaminho,
            speakerMapPath,
            caminhoDoLivro
        )
        if (!iniciou) {
            mostrarErro(R.string.conv_pausada_bloqueia)
            return
        }
        // Avisa que começou e leva ao andamento (antes a conversão iniciada aqui não tinha onde ser acompanhada).
        com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.conv_iniciada_do_leitor),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction(R.string.conv_ver) {
            startActivity(Intent(this, ConversoesActivity::class.java))
        }.show()
    }

    /** Toca o audiobook recém-gerado e leva à biblioteca (mesmo fluxo do "Ouvir" em [ConversoesActivity]). */
    private fun abrirAudiobookGerado(caminhoMp3: String) {
        // Ouvir o audiobook encerra a leitura guiada, como em [ouvirAudiobookDesteLivro].
        guidedPlayerManager?.stop()
        audiobookViewModel.iniciarPlayer(caminhoMp3, File(caminhoMp3).nameWithoutExtension)
        startActivity(Intent(this, LibraryActivity::class.java))
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
        // Ouvir o audiobook encerra a leitura guiada: a barra dela sai e o mini player do audiobook aparece.
        guidedPlayerManager?.stop()
        val nome = File(mp3).nameWithoutExtension
        playerService?.iniciar(mp3, nome) ?: run {
            val intent = Intent(this, com.jonjonesbr.audiobookgen.service.AudioPlayerService::class.java).apply {
                action = com.jonjonesbr.audiobookgen.service.AudioPlayerService.ACTION_INICIAR
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_CAMINHO, mp3)
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_NOME, nome)
            }
            startForegroundService(intent)
        }
    }

    private fun atualizarUI(state: ReaderUiState) {
        // A fila já narra outro livro: esta tela não pode entregar o texto dela ao manager (setParagraphs).
        if (guiadaEmOutroLivro()) {
            guidedPlayerManager?.let(::abrirLeitorDoLivroDaFila)
            return
        }

        // Loading
        mostrarCarregando(state.carregando, R.string.leitor_abrindo_livro)

        // Erro
        if (state.erro != null) {
            layoutErro.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            barraCapitulo.visibility = View.GONE
            tvErro.text = state.erro
            return
        }

        layoutErro.visibility = View.GONE

        val texto = viewModel.textoEfetivo()
        if (texto == null) return

        processarAutoStartLeituraGuiada(texto)

        // Mostra conteúdo
        recyclerView.visibility = View.VISIBLE

        atualizarBarraCapitulo(texto)

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
        if (!dicaLeitorTentada && !somenteLeitura) {
            dicaLeitorTentada = true
            // Uma dica por abertura: primeiro o menu ⋮, na seguinte a barra de capítulos.
            btnOpcoes.post {
                CoachMark.mostrarProxima(this, com.jonjonesbr.audiobookgen.domain.TelaDica.LEITOR) { dica ->
                    when (dica) {
                        com.jonjonesbr.audiobookgen.domain.Dica.LEITOR_GUIADA -> btnOpcoes
                        com.jonjonesbr.audiobookgen.domain.Dica.LEITOR_CAPITULOS -> barraCapitulo
                        else -> null
                    }
                }
            }
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
        // "Ler sem áudio" nunca inicia a guiada (nem o diálogo de início nem a leitura direta).
        if (somenteLeitura) return
        val controlador = guidedReadingBarController ?: return
        // Espera a limpeza: a posição salva e os capítulos valem para o texto limpo. Começar no bruto tocava o
        // parágrafo anterior ao marcado (o bruto tem lixo a mais antes), e o marcador ficava um à frente do áudio.
        val estado = viewModel.uiState.value
        if (estado.textoLimpo == null && estado.limpandoTexto) return
        // Não reabrir o diálogo ao reconectar a uma leitura em andamento (ex.: rotação da tela):
        // a intent original ainda carrega EXTRA_AUTO_START_GUIDED e o flag da instância se perde.
        if (intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false) &&
            !autoStartGuidedTriggered && !reconectandoGuided) {
            autoStartGuidedTriggered = true
            controlador.mostrarDialogPontoInicioLeituraGuiada()
        }

        // "Continuar lendo": inicia a guiada direto na posição salva (sem diálogo). Pulado se
        // estiver reconectando a uma leitura já em andamento (que já está tocando).
        if (intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false) &&
            !continuarGuiadaTriggered && !reconectandoGuided) {
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

    /** A guiada (fila "Ler tudo") narra outro livro: a tela deste livro cede o lugar ao livro que está sendo narrado. */
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

    /**
     * Barra do capítulo atual (substitui a fila de chips): "3/42 · Título", com setas para o anterior e o
     * próximo. O capítulo vem da posição de leitura (parágrafo visível ou o da leitura guiada).
     */
    private fun atualizarBarraCapitulo(texto: TextoExtraido) {
        val capitulos = texto.capitulos
        val temCapitulos = capitulos.isNotEmpty()
        barraCapitulo.visibility =
            if (temCapitulos || tvProgressoLeitura.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        tvCapituloAtual.visibility = if (temCapitulos) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ivAbrirCapitulos).visibility = tvCapituloAtual.visibility
        btnCapituloAnterior.visibility = if (temCapitulos) View.VISIBLE else View.INVISIBLE
        btnCapituloProximo.visibility = btnCapituloAnterior.visibility
        if (!temCapitulos) return

        val atual = NavegacaoCapitulos.indiceDoCapitulo(capitulos, paragrafoAtualVisivel() ?: 0).coerceAtLeast(0)
        val rotulo = getString(
            R.string.capitulo_barra, atual + 1, capitulos.size,
            NavegacaoCapitulos.tituloLimpo(capitulos[atual].titulo)
        )
        if (rotulo != textoDaBarra) {
            textoDaBarra = rotulo
            tvCapituloAtual.text = rotulo
            findViewById<View>(R.id.areaCapituloAtual).contentDescription =
                getString(R.string.cd_abrir_capitulos, rotulo)
        }
        if (atual != capituloNaBarra) {
            capituloNaBarra = atual
            btnCapituloAnterior.isEnabled = atual > 0
            btnCapituloAnterior.alpha = if (atual > 0) 1f else ALFA_SETA_DESABILITADA
            btnCapituloProximo.isEnabled = atual < capitulos.lastIndex
            btnCapituloProximo.alpha = if (atual < capitulos.lastIndex) 1f else ALFA_SETA_DESABILITADA
            // "Converter este capítulo" e a lista de capítulos usam o capítulo da posição de leitura.
            if (viewModel.uiState.value.capituloAtivo != atual) viewModel.navegarParaCapitulo(atual)
        }
    }

    /** Vai ao capítulo [indice]: na leitura guiada passa a ler dali; fora dela, rola o texto até ele. */
    private fun irParaCapitulo(indice: Int) {
        val capitulo = viewModel.textoEfetivo()?.capitulos?.getOrNull(indice) ?: return
        viewModel.navegarParaCapitulo(indice)
        val guiada = guidedReadingBarController
        if (guiada != null && (guidedPlayerManager?.state?.value?.index ?: -1) >= 0) {
            guiada.playGuidedFrom(capitulo.indiceParagrafoInicio)
        } else {
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(capitulo.indiceParagrafoInicio, 0)
        }
        recyclerView.post {
            atualizarProgressoLeitura()
            viewModel.textoEfetivo()?.let(::atualizarBarraCapitulo)
        }
    }

    /** Mostra ou esconde o aviso de carregamento com o que está acontecendo ([texto]). */
    private fun mostrarCarregando(visivel: Boolean, texto: Int) {
        layoutCarregando.visibility = if (visivel) View.VISIBLE else View.GONE
        if (visivel) tvCarregando.setText(texto)
    }

    /** Extraído de [atualizarUI] (banner de limpeza). */
    private fun atualizarPillStatusLeitura(state: ReaderUiState, texto: TextoExtraido) {
        // ── Pill de status (banner de limpeza) ────────────────────────────────
        when {
            state.limpandoTexto -> {
                mostrarCarregando(true, R.string.leitor_preparando_texto)
                bannerOtimizado.visibility = View.GONE
            }
            state.textoLimpo != null -> {
                mostrarCarregando(false, 0)
                // O resultado da limpeza aparece uma vez, por alguns segundos, e só quando algo foi tirado
                // (antes ficava sempre na tela, inclusive com "Otimização em andamento" ao abrir).
                if (!pillDaLimpezaMostrada && state.relatorioLinhasRemovidas > 0) {
                    pillDaLimpezaMostrada = true
                    bannerOtimizado.alpha = 0f
                    bannerOtimizado.visibility = View.VISIBLE
                    bannerOtimizado.animate().alpha(1f).setDuration(DURACAO_FADE_BANNER_MS).start()
                    bannerOtimizado.postDelayed({
                        bannerOtimizado.animate().alpha(0f).setDuration(DURACAO_FADE_BANNER_MS)
                            .withEndAction { bannerOtimizado.visibility = View.GONE }.start()
                    }, DURACAO_PILL_LIMPEZA_MS)
                }
                tvBannerInfo.text = resources.getQuantityString(
                    R.plurals.banner_info_trechos_capitulos_qtd,
                    state.relatorioLinhasRemovidas,
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
            fonteSerifada       = state.fonteSerifada,
            alinhamento         = state.alinhamento
        )
        return paragrafosExibidos
    }

    /** "Preparar capítulo": pré-sintetiza o capítulo do ponto de leitura atual (ver GuidedPlayerManager). */
    private fun prepararCapituloAtual() {
        val manager = guidedPlayerManager ?: return
        val andamento = com.jonjonesbr.audiobookgen.service.PreRenderBridge.state.value
        if (andamento != null && !andamento.terminou) {
            Toast.makeText(this, R.string.prerender_already, Toast.LENGTH_SHORT).show()
            return
        }
        val texto = viewModel.textoEfetivo() ?: return
        if (texto.paragrafos.isEmpty()) return
        // Do trecho em leitura (ou visível) até o fim do capítulo dele; antes partia sempre do início do livro.
        val emLeitura = manager.state.value.index.takeIf { it >= 0 }
        val inicio = (emLeitura ?: paragrafoAtualVisivel() ?: 0)
            .coerceIn(0, texto.paragrafos.lastIndex)
        val capitulo = texto.capitulos.getOrNull(texto.paragrafos[inicio].indiceCapitulo)
        val fim = (capitulo?.indiceParagrafoFim ?: (inicio + PREPARO_PARAGRAFOS_SEM_CAPITULO))
            .coerceIn(inicio, texto.paragrafos.lastIndex)
        val titulo = capitulo?.titulo ?: File(caminhoArquivo).nameWithoutExtension
        val unidades = manager.contarUnidadesParaPreparo(inicio, fim)
        if (unidades == 0) {
            Toast.makeText(this, R.string.prerender_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.prerender_confirm_title)
            .setMessage(getString(R.string.prerender_confirm_message, unidades, titulo))
            .setPositiveButton(R.string.prerender_confirm_start) { _, _ ->
                manager.prepararCapitulo(inicio, fim, titulo)
                Toast.makeText(this, R.string.prerender_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** Abre o painel pedido pela tela do livro (uma vez; o extra é consumido para não repetir na rotação). */
    private fun executarAcaoInicialSeNecessario() {
        val acao = intent.getStringExtra(EXTRA_ACAO_INICIAL) ?: return
        intent.removeExtra(EXTRA_ACAO_INICIAL)
        when (acao) {
            ACAO_INICIAL_CAPITULOS -> mostrarBottomSheetCapitulos()
            ACAO_INICIAL_PERSONAGENS -> personagensController.abrirConfiguracaoVozesPersonagens()
            ACAO_INICIAL_PRONUNCIA -> abrirDicionarioPronuncia()
        }
    }

    /** Dicionário de pronúncias do livro, com o botão de sugerir pronúncias por IA. */
    private fun abrirDicionarioPronuncia() {
        PronunciationDialog.showManager(
            this, caminhoArquivo, ::ouvirPronuncia,
            onSugerirIa = {
                val paragrafos = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }.orEmpty()
                PronunciationAiFlow.start(this, paragrafos, caminhoArquivo)
            }
        )
    }

    /** Extraído de [atualizarUI] (contador da busca). */
    private fun atualizarContadorBusca(state: ReaderUiState) {
        // ── Busca ──────────────────────────────────────────────────────────────
        val totalResultados = state.buscaResultados.size
        // Chegou um resultado novo da busca: leva o leitor direto ao primeiro achado (antes ficava
        // parado, e o "Próximo" pulava o primeiro resultado).
        if (state.buscaResultados !== ultimaBuscaRolada) {
            ultimaBuscaRolada = state.buscaResultados
            if (totalResultados > 0) recyclerView.post { scrollParaBuscaAtual() }
        }
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
            val guidedIdx = guidedPlayerManager?.state?.value?.index ?: -1
            if (reconectandoGuided && guidedIdx in 0 until paragrafosExibidos.size) {
                // Reconexão: rola para o parágrafo em execução, não para o scroll salvo.
                recyclerView.post {
                    (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(guidedIdx, 0)
                }
            } else if (!intent.getBooleanExtra(EXTRA_AUTO_START_GUIDED, false) &&
                !intent.getBooleanExtra(EXTRA_CONTINUAR_GUIADA, false)) {
                val pos = appPrefs.posicaoLeitura(caminhoArquivo, 0)
                if (pos in 1 until paragrafosExibidos.size) {
                    recyclerView.post {
                        (recyclerView.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, 0)
                    }
                }
            }
        }
    }

    /** Até quando (uptime) a rolagem automática do audiobook fica suspensa depois de o usuário rolar com o dedo. */
    private var rolagemAudiobookSuspensaAte = 0L

    /** Leitura guiada ativa e o usuário não rolou com o dedo: só o acompanhamento dela mexe na rolagem. */
    private fun leituraGuiadaConduzRolagem(): Boolean =
        (guidedPlayerManager?.state?.value?.index ?: -1) >= 0 && guidedReadingBarController?.scrollManual != true

    /** Extraído de [atualizarUI] (scroll automático do audiobook tocando, não da leitura guiada). */
    private fun sincronizarScrollAutomatico(state: ReaderUiState) {
        val controlador = guidedReadingBarController ?: return
        // Na leitura guiada o acompanhamento fino (GuidedReadingBarController) já rola; este salto até o
        // parágrafo brigava com ele (subia para o topo ou descia até o fim do parágrafo a cada troca).
        if ((guidedPlayerManager?.state?.value?.index ?: -1) >= 0) return
        val paragrafoMudouDesdeUltimoSync =
            state.paragrafoSyncAtual != controlador.ultimoScrollSyncStandard
        val podeRolarAutomaticamente = android.os.SystemClock.uptimeMillis() >= rolagemAudiobookSuspensaAte && state.syncAtivo &&
            state.paragrafoSyncAtual >= 0 && paragrafoMudouDesdeUltimoSync
        if (podeRolarAutomaticamente) {
            controlador.ultimoScrollSyncStandard = state.paragrafoSyncAtual
            (recyclerView.layoutManager as? LinearLayoutManager)
                ?.smoothScrollToPosition(recyclerView, null, state.paragrafoSyncAtual)
        }
    }

    /** Extraído de [atualizarUI] (tema de fundo do leitor). */
    private fun aplicarTemaFundo(state: ReaderUiState) {
        if (temaBarrasAplicado == state.tema) return // roda a cada atualização da tela: só repinta quando muda
        temaBarrasAplicado = state.tema
        val paleta = paletaDoLeitor(state.tema)
        findViewById<View>(R.id.readerRoot).setBackgroundColor(paleta.fundo)
        aplicarPaletaNasBarrasDoLeitor(paleta)
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

    /** A lista chegou ao fim (não rola mais para baixo, ou para a frente na rolagem horizontal) e há texto. */
    private fun fimDoLivroNaTela(): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        // Antes do primeiro layout a lista "não rola" por não ter nada medido: isso não é o fim do livro.
        val medida = ::adapter.isInitialized && adapter.itemCount > 0 && recyclerView.isLaidOut &&
            recyclerView.childCount > 0
        if (!medida) return false
        return if (lm.orientation == LinearLayoutManager.HORIZONTAL) {
            !recyclerView.canScrollHorizontally(1)
        } else {
            !recyclerView.canScrollVertically(1)
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
        // Fim do livro na tela (sem leitura em andamento): o parágrafo de cima ainda não é o último, mas não falta
        // nada a ler. Antes ficava "89% · ~1 min restantes" com o texto todo já lido.
        val noFim = state.paragrafoSyncAtual < 0 && fimDoLivroNaTela()
        val chave = if (noFim) PROGRESSO_CHAVE_FIM else indiceRef
        if (chave == progressoIndiceAplicado) return
        progressoIndiceAplicado = chave
        marcacoesController.atualizarIconeBookmark(indiceRef)

        val textoAtivo = state.textoLimpo ?: texto
        val pct = if (noFim) 1f else com.jonjonesbr.audiobookgen.util.progressoLivroPct(
            textoAtivo.paragrafos, indiceRef, textoAtivo.totalChars
        )
        val minutos = if (noFim) 0 else com.jonjonesbr.audiobookgen.util.minutosRestantesCapitulo(
            textoAtivo.paragrafos, indiceRef, textoAtivo.capitulos
        )
        tvProgressoLeitura.text = if (minutos > 0) {
            getString(R.string.progresso_leitura_com_tempo, (pct * 100).toInt(), minutos)
        } else {
            getString(R.string.progresso_leitura_sem_tempo, (pct * 100).toInt())
        }
        tvProgressoLeitura.visibility = View.VISIBLE
        // Rolar com o dedo não muda o estado da tela: a barra do capítulo acompanha por aqui.
        viewModel.textoEfetivo()?.let(::atualizarBarraCapitulo)
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
                .exibir()
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
        dialog.expandirEmPaisagem()

        val atual = capituloNaBarra.takeIf { it in texto.capitulos.indices } ?: viewModel.uiState.value.capituloAtivo
        // Marcar capítulos para converter (o que os chips faziam); não existe no "Ler sem áudio".
        val selecionados = if (somenteLeitura) null else capitulosSelecionados
        val marcarTodos = view.findViewById<TextView>(R.id.tvCapitulosMarcarTodos)
        lateinit var adapter: CapituloTocAdapter
        fun atualizarMarcarTodos() {
            marcarTodos.setText(
                if (adapter.todosVisiveisMarcados()) R.string.capitulos_desmarcar_todos else R.string.capitulos_marcar_todos
            )
        }
        adapter = CapituloTocAdapter(
            texto.capitulos, atual, selecionados,
            onCapituloTocado = { indice ->
                dialog.dismiss()
                irParaCapitulo(indice)
            },
            onSelecaoMudou = {
                if (capitulosSelecionados.isNotEmpty()) viewModel.limparSelecao()
                atualizarUI(viewModel.uiState.value)
                atualizarMarcarTodos()
            }
        )
        view.findViewById<View>(R.id.linhaCapitulosConverter).visibility =
            if (selecionados != null) View.VISIBLE else View.GONE
        atualizarMarcarTodos()
        marcarTodos.setOnClickListener { adapter.marcarTodosVisiveis(!adapter.todosVisiveisMarcados()) }
        val lista = view.findViewById<RecyclerView>(R.id.rvCapitulosToc).apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            this.adapter = adapter
        }
        // Abre já no capítulo atual, com alguns acima dele à vista.
        (lista.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset((adapter.posicaoDoAtual() - CAPITULOS_ACIMA_DO_ATUAL).coerceAtLeast(0), 0)
        if (texto.capitulos.size > MIN_CAPITULOS_PARA_BUSCA) {
            view.findViewById<android.widget.EditText>(R.id.etBuscaCapitulos).apply {
                visibility = View.VISIBLE
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) = adapter.filtrar(s?.toString().orEmpty())
                })
            }
        }

        dialog.show()
    }

    private val cloneVoiceFlow = CloneVoiceFlow(this)

    private val voiceSelectionDelegate: VoiceSelectionDelegate = VoiceSelectionDelegate(
        activity = this,
        onVoiceSelected = { voice ->
            appPrefs.vozSelecionada = voice.id
            appPrefs.motorTts = voice.engine
            guidedPlayerManager?.registrarVozEscolhida(voice.id, voice.engine)

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

            // Troca de voz refaz o trecho atual sem sair do modo manual: não é um pedido de ponto da narração.
            val guidedIndex = guidedPlayerManager?.state?.value?.index ?: -1
            if (guidedIndex >= 0) {
                guidedPlayerManager?.play(guidedIndex)
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
                audiobookViewModel.prepararConfiguracoesPython(voice.engine, chave, appPrefs.pausaMs, caminhoArquivo)
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
        val manager = guidedPlayerManager ?: return
        val (voz, motorSalvo) = manager.vozEMotorDoLivro()
        val motor = VoiceCatalog.effectiveEngine(voz, motorSalvo)
        val chave = SecurePreferences.getGeminiKeys(this)
        audiobookViewModel.pararPreview()
        audiobookViewModel.prepararConfiguracoesPython(motor, chave, appPrefs.pausaMs, caminhoArquivo)
        audiobookViewModel.ouvirTexto(voz, appPrefs.ritmo, motor, texto, caminhoArquivo)
    }

    private fun compartilharTrechoAtual() {
        val state = viewModel.uiState.value
        // Parágrafo da narração (guiada ou audiobook) ou, sem áudio, o primeiro visível na tela.
        val index = guidedPlayerManager?.state?.value?.index?.takeIf { it >= 0 }
            ?: paragrafoAtualVisivel()
            ?: -1
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
