package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.TipoRegistroErro
import com.jonjonesbr.audiobookgen.domain.resumirArquivoDeErro
import com.jonjonesbr.audiobookgen.util.ThemePrefs
import com.jonjonesbr.audiobookgen.util.mostrarErro
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.marcarComoLink
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : BasePlayerActivity() {

    private val dialogos = DialogosAbertos(this)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)

    private val appPrefs by lazy { com.jonjonesbr.audiobookgen.data.AppPrefs(this) }
    private val blacklistStore by lazy { com.jonjonesbr.audiobookgen.data.BlacklistStore(this) }
    private val pythonEngine by lazy { com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase(applicationContext) }

    // Classes extraídas (lazy: nada de recursos no construtor da Activity).
    private val blacklistDialog by lazy {
        BlacklistDialog(this, blacklistStore, onMudou = { carregarInfoBlacklist() })
    }

    // Controllers dos Ajustes (rb-11): criados no primeiro uso, sempre depois do setContentView.
    private val armazenamento: AjustesArmazenamentoController by lazy {
        AjustesArmazenamentoController(this, pythonEngine)
    }
    private val backup: AjustesBackupController by lazy {
        AjustesBackupController(this, pythonEngine, AjustesBackupController.Callbacks(
            lancarExportarConfig = { exportarConfigLauncher.launch(it) },
            lancarExportarBackup = { exportarBackupLauncher.launch(it) },
            lancarRestaurar = { restaurarLauncher.launch(it) },
            aoImportarConfig = { carregarConfigAtual() },
        ))
    }

    // Tema

    // Motor TTS
    private lateinit var radioGroupMotor: RadioGroup
    private lateinit var layoutGeminiKey: LinearLayout
    private lateinit var etGeminiKey: EditText
    private lateinit var btnSalvarChave: Button
    private lateinit var layoutElevenLabsKey: LinearLayout
    private lateinit var etElevenLabsKey: EditText
    private lateinit var btnSalvarChaveElevenLabs: Button

    // Pausas
    private lateinit var btnPausa0: Button
    private lateinit var btnPausa300: Button
    private lateinit var btnPausa600: Button
    private lateinit var btnPausa900: Button
    private lateinit var btnPausa1500: Button
    private val botoesPausa get() = listOf(
        btnPausa0 to 0, btnPausa300 to PAUSA_300_MS, btnPausa600 to PAUSA_600_MS,
        btnPausa900 to PAUSA_900_MS, btnPausa1500 to PAUSA_1500_MS
    )

    // Passos do Supertonic (qualidade x velocidade)
    private lateinit var seekSupertonicSteps: android.widget.SeekBar
    private lateinit var tvSupertonicStepsValor: TextView
    // Passos do Pocket TTS (experimental)
    private lateinit var seekPocketPassos: android.widget.SeekBar
    private val naturalidade by lazy { NaturalidadeVozUi(this) }
    private lateinit var tvPocketPassosValor: TextView
    private lateinit var swPocketMaisRapido: android.widget.CompoundButton

    // Pausa entre frases na leitura guiada com kokoro
    private lateinit var seekPausaFinalFrase: android.widget.SeekBar
    private lateinit var tvPausaFinalFraseValor: TextView
    private lateinit var chkPausasNaturais: android.widget.CompoundButton
    private lateinit var divPausaFrase: View
    private lateinit var blocoPausaFrase: View

    // Buffering da leitura guiada (profundidade de prefetch + paralelismo do kokoro)
    private lateinit var seekBufferParagrafos: android.widget.SeekBar
    private lateinit var tvBufferParagrafosValor: TextView
    private lateinit var btnParalelismoPadrao: Button
    private lateinit var btnParalelismoReduzido: Button
    private val botoesParalelismo get() = listOf(
        btnParalelismoPadrao to false, btnParalelismoReduzido to true
    )
    private lateinit var btnParalelismoSupertonicoPadrao: Button
    private lateinit var btnParalelismoSupertonicoExperimental: Button
    private val botoesParalelismoSupertonico get() = listOf(
        btnParalelismoSupertonicoPadrao to false, btnParalelismoSupertonicoExperimental to true
    )

    // Vozes Offline (card generalizado por pacote — V6 T2.2)

    // Blacklist
    private lateinit var tvBlacklistInfo: TextView

    // Logs de Erro
    private lateinit var tvLogsErroInfo: TextView

    // Abas (agrupam as ~14 seções em 4 categorias, ao invés de uma rolagem só)
    private lateinit var tabsSettings: TabLayout
    private lateinit var scrollSettings: androidx.core.widget.NestedScrollView
    private lateinit var gruposSettings: List<View>

    // Página aberta por uma linha (escolha da voz, ajustes avançados, Sobre); null = nas abas.
    private var paginaAberta: View? = null
    private val voltarDaPagina = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = fecharPagina()
    }

    // Os lançadores ficam aqui (campos) porque registram antes do STARTED; o controller só os chama.
    private val exportarConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backup.salvarConfigJson(uri)
    }

    private val exportarBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backup.salvarBackupCompleto(uri)
    }

    // "Restaurar um backup": um arquivo só; o conteúdo diz se é o backup completo (ZIP) ou só as configurações.
    private val restaurarLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        backup.restaurarDeArquivo(uri)
    }

    // Cartões de "Permissões do app" (Ajuda): os da introdução. Criado aqui, como propriedade, para registrar
    // os launchers antes de a Activity iniciar.
    private val permissoes = OnboardingPermissoes(this) { atualizarResumoPermissoes() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configurarMiniPlayerBotoes()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.AJUSTES)
        findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollSettings)
            .getChildAt(0).limitarLarguraEmTelaAmpla(840)

        onBackPressedDispatcher.addCallback(this, voltarDaPagina)
        vincularViews()
        configurarTabsSettings()
        carregarConfigAtual()
        configurarListeners()
        vincularPermissoes()
        naturalidade.vincular()
        tratarExtraFocoChave()
    }

    override fun abaPrincipal() = AbaPrincipal.AJUSTES
    override fun abasInternas(): com.google.android.material.tabs.TabLayout? = findViewById(R.id.tabsSettings)
    override fun arrasteLateralLigado() = paginaAberta == null

    override fun onResume() {
        super.onResume()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.AJUSTES) // volta de outra tela: realça de novo a aba certa
        armazenamento.aoRetomar()
        carregarInfoLogsErro()
        carregarInfoPermissaoNotificacao()
        carregarInfoOtimizacaoBateria()
        permissoes.aoRetomar() // volta da tela do Android: atualiza os cartões e o resumo da linha
    }

    private fun vincularViews() {
        findViewById<ImageButton>(R.id.btnVoltarSettings).setOnClickListener { fecharPagina() }
        findViewById<ImageButton>(R.id.btnAjudaSettings)
            .setOnClickListener { HelpActivity.start(this, HelpTopico.CONFIGURACOES) }

        radioGroupMotor = findViewById(R.id.radioGroupMotor)
        layoutGeminiKey = findViewById(R.id.layoutGeminiKey)
        etGeminiKey     = findViewById(R.id.etGeminiKey)
        btnSalvarChave  = findViewById(R.id.btnSalvarChave)
        layoutElevenLabsKey    = findViewById(R.id.layoutElevenLabsKey)
        etElevenLabsKey        = findViewById(R.id.etElevenLabsKey)
        btnSalvarChaveElevenLabs = findViewById(R.id.btnSalvarChaveElevenLabs)

        btnPausa0    = findViewById(R.id.btnPausa0)
        btnPausa300  = findViewById(R.id.btnPausa300)
        btnPausa600  = findViewById(R.id.btnPausa600)
        btnPausa900  = findViewById(R.id.btnPausa900)
        btnPausa1500 = findViewById(R.id.btnPausa1500)

        seekSupertonicSteps    = findViewById(R.id.seekSupertonicSteps)
        tvSupertonicStepsValor = findViewById(R.id.tvSupertonicStepsValor)
        seekPocketPassos       = findViewById(R.id.seekPocketPassos)
        tvPocketPassosValor    = findViewById(R.id.tvPocketPassosValor)
        swPocketMaisRapido    = findViewById(R.id.swPocketMaisRapido)

        seekPausaFinalFrase    = findViewById(R.id.seekPausaFinalFrase)
        tvPausaFinalFraseValor = findViewById(R.id.tvPausaFinalFraseValor)
        chkPausasNaturais      = findViewById(R.id.chkPausasNaturais)
        divPausaFrase          = findViewById(R.id.divPausaFrase)
        blocoPausaFrase        = findViewById(R.id.blocoPausaFrase)

        seekBufferParagrafos    = findViewById(R.id.seekBufferParagrafos)
        tvBufferParagrafosValor = findViewById(R.id.tvBufferParagrafosValor)
        btnParalelismoPadrao    = findViewById(R.id.btnParalelismoPadrao)
        btnParalelismoReduzido  = findViewById(R.id.btnParalelismoReduzido)
        btnParalelismoSupertonicoPadrao       = findViewById(R.id.btnParalelismoSupertonicoPadrao)
        btnParalelismoSupertonicoExperimental = findViewById(R.id.btnParalelismoSupertonicoExperimental)

        // Blacklist
        tvBlacklistInfo = findViewById(R.id.tvBlacklistInfo)

        // Logs de Erro
        tvLogsErroInfo = findViewById(R.id.tvLogsErroInfo)

        // Permissão de notificações
        configurarBotaoPermissaoNotificacao()

        tabsSettings   = findViewById(R.id.tabsSettings)
        scrollSettings = findViewById(R.id.scrollSettings)
        gruposSettings = listOf(
            findViewById(R.id.grupoSettingsGeral),
            findViewById(R.id.grupoSettingsAudio),
            findViewById(R.id.grupoSettingsDados),
            findViewById(R.id.grupoSettingsAjuda)
        )

        configurarBotoesAjuda()

        // Versão do app
        val versao = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "v?"
        }
        findViewById<TextView>(R.id.tvVersao).text = versao
    }

    // ── Abas ─────────────────────────────────────────────────────────────────
    // Agrupa as ~14 seções (antes empilhadas numa rolagem só) em 4 categorias.
    // Reorganização puramente visual: nenhum id/lógica de seção existente mudou,
    // só a agrupou dentro de 4 LinearLayouts (grupoSettings*) cuja visibilidade
    // segue a aba selecionada.
    private fun configurarTabsSettings() {
        mostrarGrupoSettings(0)
        // Fonte ampliada: as abas de largura igual cortariam o nome; nesse caso elas rolam.
        if (resources.configuration.fontScale >= 1.3f) {
            tabsSettings.tabMode = TabLayout.MODE_SCROLLABLE
            tabsSettings.tabGravity = TabLayout.GRAVITY_START
        }
        tabsSettings.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mostrarGrupoSettings(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        // Abrir direto numa aba (ex.: "Mais ajustes" da leitura guiada abre em Som).
        val aba = intent.getIntExtra(EXTRA_ABA, intent.getIntExtra("extra_debug_aba", -1))
        if (aba >= 0) tabsSettings.getTabAt(aba)?.select()
    }

    private fun mostrarGrupoSettings(posicao: Int) {
        if (paginaAberta != null) fecharPagina()
        gruposSettings.forEachIndexed { i, grupo -> grupo.visibility = if (i == posicao) View.VISIBLE else View.GONE }
        scrollSettings.scrollTo(0, 0)
    }

    // ── Páginas ──────────────────────────────────────────────────────────────
    // O que é longo ou técnico (escolha da voz, ajustes avançados, Sobre) abre no lugar das abas, com a
    // seta no topo para voltar; o voltar do sistema também volta para a aba.

    private fun abrirPagina(id: Int, titulo: Int) {
        gruposSettings.forEach { it.visibility = View.GONE }
        paginaAberta?.visibility = View.GONE
        paginaAberta = findViewById<View>(id).apply { visibility = View.VISIBLE }
        tabsSettings.visibility = View.GONE
        findViewById<View>(R.id.divisorTabsSettings).visibility = View.GONE
        findViewById<View>(R.id.btnVoltarSettings).visibility = View.VISIBLE
        // A Sobre não tem ajuda própria: o "?" do topo some enquanto ela está aberta.
        findViewById<View>(R.id.btnAjudaSettings).visibility = if (id == R.id.pagSobre) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvTituloSettings).setText(titulo)
        voltarDaPagina.isEnabled = true
        scrollSettings.scrollTo(0, 0)
    }

    private fun fecharPagina() {
        val pagina = paginaAberta ?: return
        pagina.visibility = View.GONE
        paginaAberta = null
        tabsSettings.visibility = View.VISIBLE
        findViewById<View>(R.id.divisorTabsSettings).visibility = View.VISIBLE
        findViewById<View>(R.id.btnVoltarSettings).visibility = View.GONE
        findViewById<View>(R.id.btnAjudaSettings).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvTituloSettings).setText(R.string.nav_ajustes)
        voltarDaPagina.isEnabled = false
        mostrarGrupoSettings(tabsSettings.selectedTabPosition)
    }

    private fun configurarBotoesAjuda() {
        // Botões de ajuda dos ajustes técnicos (o resto se explica na própria linha)
        findViewById<ImageButton>(R.id.btnAjudaPausa).setOnClickListener {
            mostrarAjuda(
                getString(R.string.settings_help_pause_title),
                getString(R.string.settings_help_pause_message)
            )
        }
    }

    private fun carregarConfigAtual() {
        // Tema (claro/escuro/sistema)
        findViewById<android.widget.TextView>(R.id.tvTemaAtual).setText(SeletorTemaSheet.nomeDoModo(ThemePrefs.load(this)))

        // Motor TTS — reflete o motor REAL salvo (antes só existiam Edge/Gemini aqui, e um
        // usuário com vozes offline/Android via "Edge" marcado, o que era mentira).
        val motor = com.jonjonesbr.audiobookgen.data.AppPrefs(this).motorTts
        val radioId = when (motor) {
            "gemini"     -> R.id.radioGemini
            "onnx"       -> R.id.radioOnnx
            "kokoro"     -> R.id.radioKokoro
            "pocket"     -> R.id.radioPocket
            "android"    -> R.id.radioAndroidTts
            "elevenlabs" -> R.id.radioElevenLabs
            else         -> R.id.radioEdge
        }
        findViewById<android.widget.RadioButton>(radioId).isChecked = true
        layoutGeminiKey.visibility = if (motor == "gemini") View.VISIBLE else View.GONE
        etGeminiKey.setText(SecurePreferences.getGeminiKeys(this))
        layoutElevenLabsKey.visibility = if (motor == "elevenlabs") View.VISIBLE else View.GONE
        etElevenLabsKey.setText(SecurePreferences.getElevenLabsKey(this))
        atualizarMotorAtual(motor)
        carregarTamanhosDoMotor()

        // Pausas
        atualizarBotoesPausa(com.jonjonesbr.audiobookgen.data.AppPrefs(this).pausaMs)

        val steps = appPrefs.supertonicSteps.coerceIn(STEPS_MIN, STEPS_MAX)
        seekSupertonicSteps.progress = steps - STEPS_MIN
        tvSupertonicStepsValor.text = resources.getQuantityString(R.plurals.settings_steps_value_qtd, steps, steps)
        ViewCompat.setStateDescription(seekSupertonicSteps, tvSupertonicStepsValor.text)

        val passosPocket = appPrefs.pocketPassos
        seekPocketPassos.progress = passosPocket - com.jonjonesbr.audiobookgen.data.AppPrefs.POCKET_PASSOS_MIN
        tvPocketPassosValor.text = resources.getQuantityString(R.plurals.settings_steps_value_qtd, passosPocket, passosPocket)
        ViewCompat.setStateDescription(seekPocketPassos, tvPocketPassosValor.text)
        swPocketMaisRapido.isChecked = appPrefs.pocketMaisRapido

        val pausaFrase = appPrefs.pausaFinalFraseMs.coerceIn(0, PAUSA_FINAL_FRASE_MS_MAX)
        seekPausaFinalFrase.progress = pausaFrase
        tvPausaFinalFraseValor.text = getString(R.string.settings_pausa_frase_value, pausaFrase)
        ViewCompat.setStateDescription(seekPausaFinalFrase, tvPausaFinalFraseValor.text)
        chkPausasNaturais.isChecked = appPrefs.pausasNaturais
        atualizarPausaFixaVisivel()

        atualizarUiBuffering()

        // Ao retomar e audiobook pronto
        atualizarTextosOuvir()

        // Blacklist
        carregarInfoBlacklist()
    }

    private fun configurarListeners() {
        configurarCreditosVoz()
        configurarAvisoDeLicenca()
        configurarLinhas()
        // Tema — salva e aplica (recria as Activities com o novo modo)
        findViewById<android.view.View>(R.id.rowTema).marcarComoBotao()
        findViewById<android.view.View>(R.id.rowTema).setOnClickListener { SeletorTemaSheet.mostrar(this) }

        // Motor TTS
        radioGroupMotor.setOnCheckedChangeListener { _, checkedId ->
            val motor = when (checkedId) {
                R.id.radioGemini     -> "gemini"
                R.id.radioOnnx       -> "onnx"
                R.id.radioKokoro     -> "kokoro"
                R.id.radioPocket     -> "pocket"
                R.id.radioAndroidTts -> "android"
                R.id.radioElevenLabs -> "elevenlabs"
                else                 -> "edge"
            }
            layoutGeminiKey.visibility = if (motor == "gemini") View.VISIBLE else View.GONE
            layoutElevenLabsKey.visibility = if (motor == "elevenlabs") View.VISIBLE else View.GONE
            atualizarMotorAtual(motor)
            salvarMotor(motor)
        }

        // Chave Gemini
        btnSalvarChave.setOnClickListener { configurarListenerSalvarChave() }
        etGeminiKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) btnSalvarChave.performClick()
            false
        }

        // Chave ElevenLabs
        btnSalvarChaveElevenLabs.setOnClickListener { configurarListenerSalvarChaveElevenLabs() }
        etElevenLabsKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) btnSalvarChaveElevenLabs.performClick()
            false
        }

        configurarListenersPausa()

        configurarSupertonicSeekbar()
        configurarPocketPassosSeekbar()
        configurarPocketMaisRapido()
        configurarPausaFinalFraseSeekbar()
        configurarBufferingLeituraGuiada()

        armazenamento.configurar()
    }

    /** Cada linha abre a sua escolha, diálogo ou página. */
    private fun configurarLinhas() {
        linha(R.id.rowMotor) { abrirPagina(R.id.pagMotor, R.string.aj_motor) }
        linha(R.id.rowAvancado) { abrirPagina(R.id.pagAvancado, R.string.aj_avancado_titulo) }
        linha(R.id.rowSobre) { abrirPagina(R.id.pagSobre, R.string.aj_sobre) }
        linha(R.id.rowVozesAparelho) { startActivity(Intent(this, DownloadCentralActivity::class.java)) }
        linha(R.id.rowPronuncias) { PronunciationDialog.showManager(this) }
        linha(R.id.rowBlacklist) { blacklistDialog.mostrarDialogoBlacklist() }
        linha(R.id.rowEstatisticas) { abrirEstatisticas() }
        linha(R.id.rowPausasNaturais) { chkPausasNaturais.toggle() }
        linha(R.id.rowPocketMaisRapido) { swPocketMaisRapido.toggle() }
        linha(R.id.rowTimbre) { TimbreDialog.show(this, appPrefs) }
        linha(R.id.rowAutoRewind) { escolherAutoRewind() }
        linha(R.id.rowPlayerConversao) { escolherPlayerConversao() }
        linha(R.id.rowBackupFazer) { backup.escolherBackupAFazer() }
        linha(R.id.rowBackupRestaurar) { backup.restaurarBackup() }
        linha(R.id.rowLogsErro) { abrirLogsErro() }
        linha(R.id.rowAprenderApp) { startActivity(Intent(this, AprenderActivity::class.java)) }
        linha(R.id.rowPermissoesApp) { abrirPagina(R.id.pagPermissoes, R.string.aj_permissoes_app) }
        linha(R.id.rowAbrirTutorial) { HelpActivity.start(this, HelpTopico.CONVERSAO) }
        linha(R.id.rowReverDicas) {
            appPrefs.dicasVistas = emptySet()
            Toast.makeText(this, R.string.aprender_dicas_reativadas, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRestaurarSom).setOnClickListener { confirmarRestaurarSom() }
    }

    private fun linha(id: Int, acao: () -> Unit) {
        findViewById<View>(id).apply {
            marcarComoBotao()
            setOnClickListener { acao() }
        }
    }

    /** Valor da linha "Voz que lê os livros": o nome curto da voz escolhida. */
    private fun atualizarMotorAtual(motor: String) {
        findViewById<TextView>(R.id.tvMotorAtual).text = when (motor) {
            "gemini" -> "Gemini"
            "onnx" -> "Supertonic"
            "kokoro" -> "Kokoro"
            "pocket" -> "Pocket"
            "android" -> "Android"
            "elevenlabs" -> "ElevenLabs"
            else -> "Microsoft"
        }
    }

    /**
     * Tamanho do download do Kokoro e do Pocket nas descrições do motor. Vem do mesmo catálogo que a Central
     * de downloads mostra antes de baixar (`tamanhoDownloadMb`); lido fora da thread principal, como o card de vozes.
     */
    private fun carregarTamanhosDoMotor() {
        lifecycleScope.launch {
            val (kokoroMb, pocketMb) = withContext(Dispatchers.IO) {
                val pacotes = com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacotesRegistrados()
                // O Pocket baixa um pacote por idioma; o do português representa os três.
                val kokoro = pacotes.firstOrNull { it.id == "kokoro" }?.tamanhoDownloadMb
                val pocket = pacotes.firstOrNull { it.id == "pocket-ptbr-int8" }?.tamanhoDownloadMb
                kokoro to pocket
            }
            // Sem tamanho (catálogo não carregou ou pacote ausente), a linha fica oculta em vez de vazia.
            findViewById<TextView>(R.id.tvMotorDescKokoro).apply {
                text = kokoroMb?.let { getString(R.string.aj_motor_desc_kokoro, it) } ?: ""
                visibility = if (kokoroMb != null) View.VISIBLE else View.GONE
            }
            findViewById<TextView>(R.id.tvMotorDescPocket).apply {
                text = pocketMb?.let { getString(R.string.aj_motor_desc_pocket, it) } ?: ""
                visibility = if (pocketMb != null) View.VISIBLE else View.GONE
            }
        }
    }

    // ── Ao ouvir: repetir ao retomar e player do audiobook pronto ───────────

    private val modosAutoRewind = com.jonjonesbr.audiobookgen.util.AutoRewindMode.entries
    private val rotulosAutoRewind =
        intArrayOf(R.string.aj_retomar_nao, R.string.aj_retomar_curto, R.string.aj_retomar_longo)
    private val valoresPlayerConversao = listOf(null, "app", "sistema")
    private val rotulosPlayerConversao =
        intArrayOf(R.string.aj_fim_perguntar, R.string.aj_fim_app, R.string.aj_fim_sistema)

    private fun atualizarTextosOuvir() {
        findViewById<TextView>(R.id.tvAutoRewindAtual)
            .setText(rotulosAutoRewind[modosAutoRewind.indexOf(appPrefs.autoRewindMode).coerceAtLeast(0)])
        val player = valoresPlayerConversao.indexOf(appPrefs.playerConversaoConcluida).coerceAtLeast(0)
        findViewById<TextView>(R.id.tvPlayerConversaoAtual).setText(rotulosPlayerConversao[player])
    }

    private fun escolherAutoRewind() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.aj_retomar)
            .setSingleChoiceItems(
                rotulosAutoRewind.map { getString(it) }.toTypedArray(),
                modosAutoRewind.indexOf(appPrefs.autoRewindMode)
            ) { dialogo, qual ->
                appPrefs.autoRewindMode = modosAutoRewind[qual]
                atualizarTextosOuvir()
                dialogo.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun escolherPlayerConversao() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.aj_fim_conversao)
            .setSingleChoiceItems(
                rotulosPlayerConversao.map { getString(it) }.toTypedArray(),
                valoresPlayerConversao.indexOf(appPrefs.playerConversaoConcluida).coerceAtLeast(0)
            ) { dialogo, qual ->
                appPrefs.playerConversaoConcluida = valoresPlayerConversao[qual]
                atualizarTextosOuvir()
                dialogo.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** "Restaurar o padrão do som": pausas, tom, repetir ao retomar, qualidade, passos e buffering. */
    private fun confirmarRestaurarSom() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.aj_restaurar_som)
            .setMessage(R.string.aj_restaurar_som_msg)
            .setPositiveButton(R.string.aj_restaurar) { _, _ ->
                appPrefs.restaurarPadroesDeSom()
                lifecycleScope.launch {
                    pythonEngine.sincronizarConfiguracoes(
                        appPrefs.motorTts, SecurePreferences.getGeminiKeys(this@SettingsActivity), appPrefs.pausaMs
                    )
                }
                carregarConfigAtual()
                naturalidade.carregar()
                Toast.makeText(this, R.string.aj_restaurado, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    // ── Motor ────────────────────────────────────────────────────────────────

    private fun salvarMotor(motor: String) {
        appPrefs.motorTts = motor
        lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(
                motor,
                SecurePreferences.getGeminiKeys(this@SettingsActivity),
                appPrefs.pausaMs
            )
        }
    }

    private fun salvarGeminiKey(chave: String): SecurePreferences.SaveResult {
        val resultado = SecurePreferences.setGeminiKeys(this, chave)
        if (resultado == SecurePreferences.SaveResult.FAILED) return resultado
        lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(appPrefs.motorTts, chave, appPrefs.pausaMs)
        }
        return resultado
    }

    // ElevenLabs não passa pelo Python (chamada HTTP direta em Kotlin), então não precisa
    // de sincronizarConfiguracoes — a chave é lida direto de SecurePreferences na hora da síntese.
    private fun salvarElevenLabsKey(chave: String): SecurePreferences.SaveResult =
        SecurePreferences.setElevenLabsKey(this, chave)

    // ── Pausa ────────────────────────────────────────────────────────────────

    private fun salvarPausa(ms: Int) {
        appPrefs.pausaMs = ms
        atualizarBotoesPausa(ms)
        lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(
                appPrefs.motorTts,
                SecurePreferences.getGeminiKeys(this@SettingsActivity),
                ms
            )
        }
    }

    private fun atualizarBotoesPausa(selecionado: Int) {
        for ((btn, ms) in botoesPausa) {
            val sel = ms == selecionado
            val colorPrimary = ContextCompat.getColor(this, R.color.color_primary)
            btn.setTextColor(if (sel) colorPrimary
                else ContextCompat.getColor(this, R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }

    // ── Supertonic Steps ────────────────────────────────────────────────────

    private fun configurarPocketPassosSeekbar() {
        seekPocketPassos.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val passos = progress + com.jonjonesbr.audiobookgen.data.AppPrefs.POCKET_PASSOS_MIN
                tvPocketPassosValor.text = resources.getQuantityString(R.plurals.settings_steps_value_qtd, passos, passos)
                ViewCompat.setStateDescription(sb, tvPocketPassosValor.text)
                if (fromUser) appPrefs.pocketPassos = passos
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun configurarPocketMaisRapido() {
        swPocketMaisRapido.setOnCheckedChangeListener { _, marcado -> appPrefs.pocketMaisRapido = marcado }
    }

    private fun configurarSupertonicSeekbar() {
        seekSupertonicSteps.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val steps = progress + STEPS_MIN
                tvSupertonicStepsValor.text = resources.getQuantityString(R.plurals.settings_steps_value_qtd, steps, steps)
                ViewCompat.setStateDescription(sb, tvSupertonicStepsValor.text)
                appPrefs.supertonicSteps = steps
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        tvSupertonicStepsValor.setOnClickListener {
            val current = seekSupertonicSteps.progress + STEPS_MIN
            val input = android.widget.EditText(this).apply {
                setText(current.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                selectAll()
                setPadding(DIALOG_PADDING_H, DIALOG_PADDING_V, DIALOG_PADDING_H, DIALOG_PADDING_V)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_supertonic_steps_title)
                .setMessage(getString(R.string.dialog_valor_entre, STEPS_MIN, STEPS_MAX))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val texto = input.text.toString().trim()
                    val valor = texto.toIntOrNull()
                    if (valor != null && valor in STEPS_MIN..STEPS_MAX) {
                        seekSupertonicSteps.progress = valor - STEPS_MIN
                        tvSupertonicStepsValor.text = resources.getQuantityString(R.plurals.settings_steps_value_qtd, valor, valor)
                        appPrefs.supertonicSteps = valor
                    } else {
                        android.widget.Toast.makeText(
                            this, getString(R.string.toast_valor_invalido, STEPS_MIN, STEPS_MAX),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .exibir()
        }
    }

    // ── Pausa entre frases (kokoro) ─────────────────────────────────────────

    /** Com as pausas naturais ligadas a pausa fixa não vale: o bloco some até elas serem desligadas. */
    private fun atualizarPausaFixaVisivel() {
        val visivel = if (appPrefs.pausasNaturais) View.GONE else View.VISIBLE
        divPausaFrase.visibility = visivel
        blocoPausaFrase.visibility = visivel
    }

    private fun configurarPausaFinalFraseSeekbar() {
        chkPausasNaturais.setOnCheckedChangeListener { _, marcado ->
            appPrefs.pausasNaturais = marcado
            atualizarPausaFixaVisivel()
        }
        seekPausaFinalFrase.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                tvPausaFinalFraseValor.text = getString(R.string.settings_pausa_frase_value, progress)
                ViewCompat.setStateDescription(sb, tvPausaFinalFraseValor.text)
                appPrefs.pausaFinalFraseMs = progress
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    // ── Buffering da leitura guiada (profundidade de prefetch + paralelismo kokoro) ──

    private fun configurarBufferingLeituraGuiada() {
        seekBufferParagrafos.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                // Posição 0 = automático (sentinela); 1..8 = valor explícito, sobrepondo o
                // padrão interno de TODOS os motores (ver GuidedPlayerManager.profundidadePrefetchEfetiva).
                appPrefs.bufferParagrafosAdiante = if (progress == 0) {
                    com.jonjonesbr.audiobookgen.data.AppPrefs.BUFFER_ADIANTE_AUTOMATICO
                } else {
                    progress
                }
                atualizarLabelBufferParagrafos(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        for ((btn, reduzido) in botoesParalelismo) {
            btn.setOnClickListener {
                appPrefs.paralelismoKokoroReduzido = reduzido
                atualizarBotoesParalelismo(reduzido)
            }
        }

        btnParalelismoSupertonicoPadrao.setOnClickListener {
            appPrefs.paralelismoSupertonicoAumentado = false
            atualizarBotoesParalelismoSupertonico(false)
        }
        btnParalelismoSupertonicoExperimental.setOnClickListener {
            // EXPERIMENTAL de verdade (thread-safety nativa nunca validada) — confirma antes
            // de ativar, mesmo padrão do aviso de licença na importação BYOM.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.dialog_paralelismo_experimental_titulo)
                .setMessage(R.string.dialog_paralelismo_experimental_mensagem)
                .setPositiveButton(R.string.btn_ativar_mesmo_assim) { _, _ ->
                    appPrefs.paralelismoSupertonicoAumentado = true
                    atualizarBotoesParalelismoSupertonico(true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .exibir()
        }
    }

    private fun atualizarUiBuffering() {
        val override = appPrefs.bufferParagrafosAdiante
        val progress = if (override == com.jonjonesbr.audiobookgen.data.AppPrefs.BUFFER_ADIANTE_AUTOMATICO) {
            0
        } else {
            override.coerceIn(0, seekBufferParagrafos.max)
        }
        seekBufferParagrafos.progress = progress
        atualizarLabelBufferParagrafos(progress)
        atualizarBotoesParalelismo(appPrefs.paralelismoKokoroReduzido)
        atualizarBotoesParalelismoSupertonico(appPrefs.paralelismoSupertonicoAumentado)
    }

    private fun atualizarBotoesParalelismoSupertonico(aumentado: Boolean) {
        for ((btn, valor) in botoesParalelismoSupertonico) {
            val sel = valor == aumentado
            btn.setTextColor(ContextCompat.getColor(this,
                if (sel) R.color.color_primary else R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }

    private fun atualizarLabelBufferParagrafos(progress: Int) {
        tvBufferParagrafosValor.text = if (progress == 0) {
            getString(R.string.settings_buffer_paragrafos_valor_automatico)
        } else {
            getString(R.string.settings_buffer_paragrafos_valor, progress)
        }
        ViewCompat.setStateDescription(seekBufferParagrafos, tvBufferParagrafosValor.text)
    }

    private fun atualizarBotoesParalelismo(reduzido: Boolean) {
        for ((btn, valor) in botoesParalelismo) {
            val sel = valor == reduzido
            btn.setTextColor(ContextCompat.getColor(this,
                if (sel) R.color.color_primary else R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }

    /** Extraído de [configurarListeners] pra manter dentro do limite de linhas do detekt. */
    private fun configurarListenerSalvarChave() {
        val chave = etGeminiKey.text.toString().trim()
        when (salvarGeminiKey(chave)) {
            SecurePreferences.SaveResult.SECURE -> Toast.makeText(
                this,
                if (chave.isNotBlank()) R.string.toast_key_saved else R.string.toast_key_removed,
                Toast.LENGTH_SHORT
            ).show()
            SecurePreferences.SaveResult.LOCAL_FALLBACK -> Toast.makeText(
                this,
                if (chave.isNotBlank()) R.string.toast_key_saved_local_fallback else R.string.toast_key_removed,
                Toast.LENGTH_LONG
            ).show()
            SecurePreferences.SaveResult.FAILED -> mostrarErro(R.string.toast_key_secure_save_failed)
        }
    }

    /** Extraído de [configurarListeners] pra manter dentro do limite de linhas do detekt. */
    private fun configurarListenerSalvarChaveElevenLabs() {
        val chave = etElevenLabsKey.text.toString().trim()
        when (salvarElevenLabsKey(chave)) {
            SecurePreferences.SaveResult.SECURE -> Toast.makeText(
                this,
                if (chave.isNotBlank()) R.string.toast_key_saved else R.string.toast_key_removed,
                Toast.LENGTH_SHORT
            ).show()
            SecurePreferences.SaveResult.LOCAL_FALLBACK -> Toast.makeText(
                this,
                if (chave.isNotBlank()) R.string.toast_key_saved_local_fallback else R.string.toast_key_removed,
                Toast.LENGTH_LONG
            ).show()
            SecurePreferences.SaveResult.FAILED -> mostrarErro(R.string.toast_key_secure_save_failed)
        }
    }

    private fun mostrarAjuda(titulo: String, texto: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(texto)
                .setPositiveButton(R.string.dialog_ok, null)
                .exibir()
    }

    private fun configurarCreditosVoz() {
        findViewById<android.widget.TextView>(R.id.tvCreditosVoz).marcarComoLink()
        findViewById<android.widget.TextView>(R.id.tvCreditosVoz).setOnClickListener {
            val creditos = resources.getStringArray(R.array.creditos_voz_entradas)
            mostrarAjuda(
                getString(R.string.label_creditos_voz),
                creditos.joinToString("\n\n")
            )
        }
    }

    /** Licença do app (GPL §0 e §5(d)): o toque abre o aviso legal com o texto completo. */
    private fun configurarAvisoDeLicenca() {
        val tvLicenca = findViewById<TextView>(R.id.tvSobreLicenca)
        tvLicenca.marcarComoLink()
        tvLicenca.setOnClickListener { mostrarAvisoDeLicenca(this, dialogos) }
    }

    private fun carregarInfoLogsErro() {
        tvLogsErroInfo.text = getString(R.string.logs_erro_calculando)
        lifecycleScope.launch {
            // Encerramentos normais do sistema (pouca memória, limpeza) não contam: não são defeito do app.
            val count = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.util.CrashLogWriter.getLogFiles(this@SettingsActivity)
                    .count { resumirArquivoDeErro(it).tipo != TipoRegistroErro.ENCERRAMENTO_NORMAL }
            }
            if (count == 0) {
                tvLogsErroInfo.text = getString(R.string.logs_erro_nenhum)
            } else {
                tvLogsErroInfo.text = resources.getQuantityString(R.plurals.logs_erro_contagem, count, count)
            }
        }
    }

    private fun configurarBotaoPermissaoNotificacao() {
        findViewById<Button>(R.id.btnConfigOtimizacaoBateria)
            .setOnClickListener { abrirConfiguracaoOtimizacaoBateria() }
        findViewById<Button>(R.id.btnAbrirConfigNotificacoes).setOnClickListener {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            try {
                startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                mostrarErro(R.string.toast_config_notificacoes_indisponivel)
            }
        }
    }


    // ── Permissões do app (Ajuda) ────────────────────────────────────────────
    // Página aberta pela linha: cada cartão age sozinho (sem "Começar" nem sequência da introdução).

    private fun vincularPermissoes() {
        // O layout deixa o contêiner oculto (a introdução o mostra no adapter); aqui a página já o exibe.
        val cartoes = findViewById<View>(R.id.containerPermissoes)
        cartoes.visibility = View.VISIBLE
        permissoes.vincular(cartoes)
    }

    /** Resumo da linha: "Tudo liberado" ou quantas permissões ainda faltam. */
    private fun atualizarResumoPermissoes() {
        val pendentes = permissoes.pendentes().size
        findViewById<TextView>(R.id.tvPermissoesResumo).text = if (pendentes == 0) {
            getString(R.string.perm_tudo_liberado)
        } else {
            resources.getQuantityString(R.plurals.perm_pendentes_qtd, pendentes, pendentes)
        }
    }

    /** Aviso no topo de "Voz e leitura" só quando o Android pode pausar conversões longas. */
    private fun carregarInfoOtimizacaoBateria() {
        val ignorada = com.jonjonesbr.audiobookgen.util.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        findViewById<View>(R.id.cardAvisoBateria).visibility = if (ignorada) View.GONE else View.VISIBLE
    }

    private fun abrirConfiguracaoOtimizacaoBateria() {
        PermissoesDoApp.explicarBateria(this)
    }
    /** Aviso no topo de "Voz e leitura" só quando as notificações estão desligadas. */
    private fun carregarInfoPermissaoNotificacao() {
        val habilitadas = NotificationManagerCompat.from(this).areNotificationsEnabled()
        findViewById<View>(R.id.cardAvisoNotificacao).visibility = if (habilitadas) View.GONE else View.VISIBLE
    }

    /** Se aberta com extra "focar_chave_gemini"/"focar_chave_elevenlabs" (seleção de voz sem
     * chave), rola até o campo da chave e o foca — cumpre a promessa do toast de configuração. */
    private fun tratarExtraFocoChave() {
        val campo = when {
            intent.getBooleanExtra("focar_chave_gemini", false) -> layoutGeminiKey
            intent.getBooleanExtra("focar_chave_elevenlabs", false) -> layoutElevenLabsKey
            else -> return
        }
        abrirPagina(R.id.pagMotor, R.string.aj_motor)
        scrollSettings.post {
            val alvo = IntArray(2)
            val conteudo = IntArray(2)
            campo.getLocationInWindow(alvo)
            scrollSettings.getChildAt(0)?.getLocationInWindow(conteudo)
            scrollSettings.smoothScrollTo(0, (alvo[1] - conteudo[1]).coerceAtLeast(0))
            val edit = if (campo === layoutGeminiKey) etGeminiKey else etElevenLabsKey
            edit.requestFocus()
            val teclado = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            teclado?.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun abrirLogsErro() {
        val intent = Intent(this, ErrorLogsActivity::class.java)
        startActivity(intent)
    }

    private fun abrirEstatisticas() {
        startActivity(Intent(this, StatisticsActivity::class.java))
    }

    // ── Blacklist ────────────────────────────────────────────────────────────

    private fun carregarInfoBlacklist() {
        val count = blacklistStore.count()
        tvBlacklistInfo.text = if (count == 0) {
            getString(R.string.blacklist_empty)
        } else {
            resources.getQuantityString(R.plurals.blacklist_terms_count, count, count)
        }
    }

    private fun configurarListenersPausa() {
        // Pausas
        for ((btn, ms) in botoesPausa) {
            btn.setOnClickListener { salvarPausa(ms) }
        }
    }

    companion object {
        /** Aba inicial (0 = Voz e leitura, 1 = Som, 2 = Dados, 3 = Ajuda). */
        const val EXTRA_ABA = "extra_aba"
        const val ABA_SOM = 1
        private const val JSON_INDENT = 4
        private const val STEPS_MIN = 2
        // O mesmo teto do motor (OnnxTtsEngine limita a AppPrefs.SUPERTONIC_STEPS_MAX).
        private const val STEPS_MAX = com.jonjonesbr.audiobookgen.data.AppPrefs.SUPERTONIC_STEPS_MAX
        // Teto do slider de pausa entre frases (kokoro) — 2s já é uma pausa dramática pra uma
        // frase só; casa com o android:max="2000" do SeekBar (progresso = ms direto, sem offset).
        private const val PAUSA_FINAL_FRASE_MS_MAX = 2000
        private const val DIALOG_PADDING_H = 48
        private const val DIALOG_PADDING_V = 24
        private const val PAUSA_300_MS = 300
        private const val PAUSA_600_MS = 600
        private const val PAUSA_900_MS = 900
        private const val PAUSA_1500_MS = 1500
    }
}
