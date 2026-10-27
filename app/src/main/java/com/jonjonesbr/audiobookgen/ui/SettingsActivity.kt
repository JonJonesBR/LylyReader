package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.util.ThemePrefs
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.marcarComoLink
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
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
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

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

    // Pausa entre frases na leitura guiada com kokoro
    private lateinit var seekPausaFinalFrase: android.widget.SeekBar
    private lateinit var tvPausaFinalFraseValor: TextView
    private lateinit var chkPausasNaturais: android.widget.CompoundButton

    // Buffering da leitura guiada (profundidade de prefetch + paralelismo do kokoro)
    private lateinit var seekBufferParagrafos: android.widget.SeekBar
    private lateinit var tvBufferParagrafosValor: TextView
    private lateinit var btnParalelismoPadrao: Button
    private lateinit var btnParalelismoReduzido: Button
    private lateinit var btnResetarBuffering: Button
    private val botoesParalelismo get() = listOf(
        btnParalelismoPadrao to false, btnParalelismoReduzido to true
    )
    private lateinit var btnParalelismoSupertonicoPadrao: Button
    private lateinit var btnParalelismoSupertonicoExperimental: Button
    private val botoesParalelismoSupertonico get() = listOf(
        btnParalelismoSupertonicoPadrao to false, btnParalelismoSupertonicoExperimental to true
    )

    // Cache
    private lateinit var tvCacheInfo: TextView
    private lateinit var btnLimparCache: Button
    private lateinit var tvCacheAudioInfo: TextView
    private lateinit var seekTetoCache: android.widget.SeekBar
    private lateinit var tvTetoCacheValor: TextView

    // Vozes Offline (card generalizado por pacote — V6 T2.2)

    // Blacklist
    private lateinit var tvBlacklistInfo: TextView

    // Logs de Erro
    private lateinit var tvLogsErroInfo: TextView

    // Backup (configurações em JSON ou backup completo em ZIP)
    private val backupUseCase by lazy { com.jonjonesbr.audiobookgen.domain.BackupUseCase(this) }

    // Abas (agrupam as ~14 seções em 4 categorias, ao invés de uma rolagem só)
    private lateinit var tabsSettings: TabLayout
    private lateinit var scrollSettings: androidx.core.widget.NestedScrollView
    private lateinit var gruposSettings: List<View>

    // Página aberta por uma linha (escolha da voz, ajustes avançados, Sobre); null = nas abas.
    private var paginaAberta: View? = null
    private val voltarDaPagina = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = fecharPagina()
    }

    private val exportarConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        salvarConfigJson(uri)
    }

    private val exportarBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        salvarBackupCompleto(uri)
    }

    // "Restaurar um backup": um arquivo só; o conteúdo diz se é o backup completo (ZIP) ou só as configurações.
    private val restaurarLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        restaurarDeArquivo(uri)
    }

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
        naturalidade.vincular()
        tratarExtraFocoChave()
    }

    override fun onResume() {
        super.onResume()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.AJUSTES) // volta de outra tela: realça de novo a aba certa
        carregarInfoCache()
        carregarInfoLogsErro()
        carregarInfoPermissaoNotificacao()
        carregarInfoOtimizacaoBateria()
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

        seekPausaFinalFrase    = findViewById(R.id.seekPausaFinalFrase)
        tvPausaFinalFraseValor = findViewById(R.id.tvPausaFinalFraseValor)
        chkPausasNaturais      = findViewById(R.id.chkPausasNaturais)

        seekBufferParagrafos    = findViewById(R.id.seekBufferParagrafos)
        tvBufferParagrafosValor = findViewById(R.id.tvBufferParagrafosValor)
        btnParalelismoPadrao    = findViewById(R.id.btnParalelismoPadrao)
        btnParalelismoReduzido  = findViewById(R.id.btnParalelismoReduzido)
        btnParalelismoSupertonicoPadrao       = findViewById(R.id.btnParalelismoSupertonicoPadrao)
        btnParalelismoSupertonicoExperimental = findViewById(R.id.btnParalelismoSupertonicoExperimental)
        btnResetarBuffering     = findViewById(R.id.btnResetarBuffering)

        tvCacheInfo   = findViewById(R.id.tvCacheInfo)
        btnLimparCache = findViewById(R.id.btnLimparCache)
        tvCacheAudioInfo = findViewById(R.id.tvCacheAudioInfo)
        seekTetoCache = findViewById(R.id.seekTetoCache)
        tvTetoCacheValor = findViewById(R.id.tvTetoCacheValor)


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
        findViewById<TextView>(R.id.tvVersaoLinha).text = versao
    }

    // ── Abas ─────────────────────────────────────────────────────────────────
    // Agrupa as ~14 seções (antes empilhadas numa rolagem só) em 4 categorias.
    // Reorganização puramente visual: nenhum id/lógica de seção existente mudou,
    // só a agrupou dentro de 4 LinearLayouts (grupoSettings*) cuja visibilidade
    // segue a aba selecionada.
    private fun configurarTabsSettings() {
        mostrarGrupoSettings(0)
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

        val pausaFrase = appPrefs.pausaFinalFraseMs.coerceIn(0, PAUSA_FINAL_FRASE_MS_MAX)
        seekPausaFinalFrase.progress = pausaFrase
        tvPausaFinalFraseValor.text = getString(R.string.settings_pausa_frase_value, pausaFrase)
        ViewCompat.setStateDescription(seekPausaFinalFrase, tvPausaFinalFraseValor.text)
        chkPausasNaturais.isChecked = appPrefs.pausasNaturais
        atualizarPausaFixaHabilitada()

        atualizarUiBuffering()

        // Ao retomar e audiobook pronto
        atualizarTextosOuvir()

        // Blacklist
        carregarInfoBlacklist()
    }

    private fun configurarListeners() {
        configurarCreditosVoz()
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
        configurarPausaFinalFraseSeekbar()
        configurarBufferingLeituraGuiada()

        // Cache
        btnLimparCache.setOnClickListener { limparCache() }
        configurarTetoCache()
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
        linha(R.id.rowTimbre) { TimbreDialog.show(this, appPrefs) }
        linha(R.id.rowAutoRewind) { escolherAutoRewind() }
        linha(R.id.rowPlayerConversao) { escolherPlayerConversao() }
        linha(R.id.rowBackupFazer) { escolherBackupAFazer() }
        linha(R.id.rowBackupRestaurar) { restaurarBackup() }
        linha(R.id.rowLogsErro) { abrirLogsErro() }
        linha(R.id.rowAprenderApp) { startActivity(Intent(this, AprenderActivity::class.java)) }
        linha(R.id.rowAbrirTutorial) { HelpActivity.start(this, HelpTopico.CONVERSAO) }
        linha(R.id.rowReverTour) {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REVISAO, true))
        }
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

    /** Com as pausas naturais ligadas a pausa fixa não vale: o controle fica desabilitado. */
    private fun atualizarPausaFixaHabilitada() {
        val fixa = !appPrefs.pausasNaturais
        seekPausaFinalFrase.isEnabled = fixa
        tvPausaFinalFraseValor.alpha = if (fixa) 1f else ALFA_DESABILITADO
    }

    private fun configurarPausaFinalFraseSeekbar() {
        chkPausasNaturais.setOnCheckedChangeListener { _, marcado ->
            appPrefs.pausasNaturais = marcado
            atualizarPausaFixaHabilitada()
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

        btnResetarBuffering.setOnClickListener {
            appPrefs.resetarBufferingLeituraGuiada()
            atualizarUiBuffering()
            Toast.makeText(this, R.string.toast_buffering_resetado, Toast.LENGTH_SHORT).show()
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

    // ── Cache ────────────────────────────────────────────────────────────────

    private fun rotuloTetoCache(mb: Int): String = when {
        mb <= 0 -> getString(R.string.cache_teto_sem_limite)
        mb >= 1024 -> getString(R.string.cache_teto_gb, mb / 1024)
        else -> getString(R.string.cache_teto_mb, mb)
    }

    /** Slider do teto do cache de áudio das vozes (256 MB … 4 GB, ou sem limite). */
    private fun configurarTetoCache() {
        val opcoes = com.jonjonesbr.audiobookgen.tts.CachePolicy.TETOS_MB
        val atual = appPrefs.tetoCacheAudioMb
        val indice = opcoes.indexOf(atual).takeIf { it >= 0 }
            ?: opcoes.indexOf(com.jonjonesbr.audiobookgen.tts.CachePolicy.TETO_PADRAO_MB)
        seekTetoCache.progress = indice
        tvTetoCacheValor.text = rotuloTetoCache(opcoes[indice])
        ViewCompat.setStateDescription(seekTetoCache, tvTetoCacheValor.text)
        seekTetoCache.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                tvTetoCacheValor.text = rotuloTetoCache(opcoes[progress])
                ViewCompat.setStateDescription(sb, tvTetoCacheValor.text)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val mb = opcoes[seekTetoCache.progress]
                appPrefs.tetoCacheAudioMb = mb
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        com.jonjonesbr.audiobookgen.tts.TtsChunkCache.aparar(
                            applicationContext, com.jonjonesbr.audiobookgen.tts.CachePolicy.tetoEmBytes(mb)
                        )
                    }
                    atualizarInfoCacheAudio()
                }
            }
        })
        atualizarInfoCacheAudio()
    }

    private fun atualizarInfoCacheAudio() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.tts.TtsChunkCache.tamanhoBytes(applicationContext)
            }
            tvCacheAudioInfo.text = getString(
                R.string.cache_audio_info,
                android.text.format.Formatter.formatShortFileSize(this@SettingsActivity, bytes),
                rotuloTetoCache(appPrefs.tetoCacheAudioMb)
            )
        }
    }

    private fun carregarInfoCache() {
        tvCacheInfo.text = getString(R.string.cache_calculando)
        lifecycleScope.launch {
            val info = pythonEngine.obterInfoCache()
            tvCacheInfo.text = when {
                info == null -> getString(R.string.cache_unavailable)
                info.arquivos == 0 -> getString(R.string.cache_empty)
                else -> getString(R.string.cache_info_chunks, info.tamanhoMb, info.arquivos)
            }
        }
    }

    private fun mostrarAjuda(titulo: String, texto: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(texto)
                .setPositiveButton(R.string.dialog_ok, null)
                .exibir()
    }

    private fun limparCache() {
        btnLimparCache.isEnabled = false
        tvCacheInfo.text = getString(R.string.cache_cleaning)
        lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    val pyRemoved = pythonEngine.limparCachePython()
                    // Limpa também o cache ONNX do lado Kotlin (leitura guiada + conversão).
                    val ktRemoved = com.jonjonesbr.audiobookgen.tts.TtsChunkCache.clear(applicationContext)
                    pyRemoved + ktRemoved
                }
                tvCacheInfo.text = resources.getQuantityString(R.plurals.cache_empty_removed_qtd, removed, removed)
                atualizarInfoCacheAudio()
                btnLimparCache.isEnabled = true
                Toast.makeText(this@SettingsActivity, R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                tvCacheInfo.text = getString(R.string.cache_clean_error)
                btnLimparCache.isEnabled = true
            }
        }
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

    private fun carregarInfoLogsErro() {
        tvLogsErroInfo.text = getString(R.string.logs_erro_calculando)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.util.CrashLogWriter.getLogFiles(this@SettingsActivity).size
            }
            if (count == 0) {
                tvLogsErroInfo.text = getString(R.string.logs_erro_vazio)
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


    /** Aviso no topo de "Voz e leitura" só quando o Android pode pausar conversões longas. */
    private fun carregarInfoOtimizacaoBateria() {
        val ignorada = com.jonjonesbr.audiobookgen.util.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        findViewById<View>(R.id.cardAvisoBateria).visibility = if (ignorada) View.GONE else View.VISIBLE
    }

    private fun abrirConfiguracaoOtimizacaoBateria() {
        if (!com.jonjonesbr.audiobookgen.util.BatteryOptimizationHelper.abrir(this)) {
            mostrarErro(R.string.toast_config_bateria_indisponivel)
        }
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

    /** "Fazer backup": tudo (recomendado) ou só as configurações. */
    private fun escolherBackupAFazer() {
        val opcoes = arrayOf(getString(R.string.aj_backup_tudo), getString(R.string.aj_backup_so_config))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.aj_backup_fazer)
            .setItems(opcoes) { _, qual -> if (qual == 0) exportarBackupCompleto() else exportarConfiguracoes() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun restaurarBackup() {
        try {
            restaurarLauncher.launch(
                arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar restauracao", e)
            mostrarErro(getString(R.string.toast_erro_importar_backup, getString(mensagemDeErro(e))))
        }
    }

    /** ZIP (começa com "PK") é o backup completo; o resto é tratado como o JSON das configurações. */
    private fun restaurarDeArquivo(uri: Uri) {
        lifecycleScope.launch {
            val ehZip = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.read() == 'P'.code && it.read() == 'K'.code }
                }.getOrNull() == true
            }
            if (ehZip) restaurarBackupCompleto(uri) else carregarConfigJson(uri)
        }
    }

    private fun exportarConfiguracoes() {
        try {
            exportarConfigLauncher.launch("lylyreader-config.json")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar exportacao", e)
            mostrarErro(getString(R.string.toast_erro_exportar, getString(mensagemDeErro(e))))
        }
    }

    private fun salvarConfigJson(uri: Uri) {
        try {
            val json = com.jonjonesbr.audiobookgen.data.AppPrefsBackup.paraJson(appPrefs)
            val stream = contentResolver.openOutputStream(uri)
            if (stream == null) {
                Log.w("SettingsActivity", "openOutputStream retornou null — sem permissão de escrita no provedor")
                mostrarErro(R.string.toast_erro_exportar_null)
                return
            }
            stream.use { outputStream ->
                outputStream.write(json.toString(JSON_INDENT).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.toast_backup_exportado, Toast.LENGTH_SHORT).show()
        } catch (e: java.io.IOException) {
            Log.w("SettingsActivity", "Erro ao salvar configuracoes", e)
            mostrarErro(getString(R.string.toast_erro_exportar, getString(mensagemDeErro(e))))
        } catch (e: org.json.JSONException) {
            Log.w("SettingsActivity", "Erro ao salvar configuracoes", e)
            mostrarErro(getString(R.string.toast_erro_exportar, getString(mensagemDeErro(e))))
        }
    }

    /** Lê e parseia o JSON do Uri. Roda em IO; devolve (json, erro) para a main mostrar o erro. */
    private fun lerJsonDeUri(uri: Uri): Pair<JSONObject?, Exception?> {
        try {
            val jsonStr = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: error("Input stream is null")
            return JSONObject(jsonStr) to null
        } catch (e: java.io.IOException) {
            Log.w("SettingsActivity", "Erro ao carregar configuracoes", e)
            return null to e
        } catch (e: java.lang.IllegalStateException) {
            Log.w("SettingsActivity", "Erro ao carregar configuracoes", e)
            return null to e
        } catch (e: org.json.JSONException) {
            Log.w("SettingsActivity", "Erro ao parsear JSON", e)
            return null to e
        }
    }

    private fun carregarConfigJson(uri: Uri) {
        lifecycleScope.launch {
            val (json, erro) = withContext(Dispatchers.IO) { lerJsonDeUri(uri) }
            if (erro != null) {
                mostrarErro(getString(R.string.toast_erro_importar, getString(mensagemDeErro(erro))))
                return@launch
            }
            if (json == null) return@launch

            com.jonjonesbr.audiobookgen.data.AppPrefsBackup.aplicar(appPrefs, json)

            // Sincroniza com o motor Python
            launch {
                pythonEngine.sincronizarConfiguracoes(
                    appPrefs.motorTts,
                    SecurePreferences.getGeminiKeys(this@SettingsActivity),
                    appPrefs.pausaMs
                )
            }

            // Recarrega configs na UI
            carregarConfigAtual()

            // Dialog de sucesso
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.dialog_importar_sucesso_titulo)
                .setMessage(R.string.dialog_importar_sucesso_mensagem)
                .setPositiveButton(R.string.dialog_ok, null)
                .exibir()
        }
    }

    private fun exportarBackupCompleto() {
        try {
            exportarBackupLauncher.launch("lylyreader-backup.zip")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar exportacao de backup", e)
            val msg = getString(R.string.toast_erro_exportar_backup, getString(mensagemDeErro(e)))
            mostrarErro(msg)
        }
    }

    private fun salvarBackupCompleto(uri: Uri) {
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { backupUseCase.exportar(uri) }
            resultado.onSuccess {
                val msg = R.string.toast_backup_completo_exportado
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Log.w("SettingsActivity", "Erro ao exportar backup completo", e)
                mostrarErro(getString(R.string.toast_erro_exportar_backup, getString(mensagemDeErro(e))))
            }
        }
    }

    private fun restaurarBackupCompleto(uri: Uri) {
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { backupUseCase.importar(uri) }
            resultado.onSuccess { resumo -> mostrarResumoBackupImportado(resumo) }
                .onFailure { e ->
                    Log.w("SettingsActivity", "Erro ao importar backup completo", e)
                    val motivo = (e as? com.jonjonesbr.audiobookgen.domain.BackupException)?.motivo
                    val msg = when (motivo) {
                        com.jonjonesbr.audiobookgen.domain.MotivoBackup.ARQUIVO_INVALIDO -> getString(R.string.backup_erro_arquivo_invalido)
                        com.jonjonesbr.audiobookgen.domain.MotivoBackup.VERSAO_MAIS_NOVA -> getString(R.string.backup_erro_versao_mais_nova)
                        com.jonjonesbr.audiobookgen.domain.MotivoBackup.MUITO_GRANDE -> getString(R.string.backup_erro_muito_grande)
                        null -> getString(R.string.toast_erro_importar_backup, getString(mensagemDeErro(e)))
                    }
                    mostrarErro(msg)
                }
        }
    }

    private fun mostrarResumoBackupImportado(resumo: com.jonjonesbr.audiobookgen.domain.BackupUseCase.ImportSummary) {
        if (resumo.configImportado) {
            lifecycleScope.launch {
                pythonEngine.sincronizarConfiguracoes(
                    appPrefs.motorTts,
                    SecurePreferences.getGeminiKeys(this@SettingsActivity),
                    appPrefs.pausaMs
                )
            }
            carregarConfigAtual()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_importar_backup_sucesso_titulo)
            .setMessage(
                getString(
                    R.string.dialog_importar_backup_sucesso_mensagem,
                    resumo.bookmarks,
                    resumo.highlights,
                    resumo.progresso
                )
            )
            .setPositiveButton(R.string.dialog_ok, null)
            .exibir()
    }

    companion object {
        /** Aba inicial (0 = Voz e leitura, 1 = Som, 2 = Backup e espaço, 3 = Ajuda). */
        const val EXTRA_ABA = "extra_aba"
        const val ABA_SOM = 1
        private const val JSON_INDENT = 4
        private const val STEPS_MIN = 2
        // O mesmo teto do motor (OnnxTtsEngine limita a AppPrefs.SUPERTONIC_STEPS_MAX).
        private const val STEPS_MAX = com.jonjonesbr.audiobookgen.data.AppPrefs.SUPERTONIC_STEPS_MAX
        // Teto do slider de pausa entre frases (kokoro) — 2s já é uma pausa dramática pra uma
        // frase só; casa com o android:max="2000" do SeekBar (progresso = ms direto, sem offset).
        private const val PAUSA_FINAL_FRASE_MS_MAX = 2000
        private const val ALFA_DESABILITADO = 0.4f
        private const val DIALOG_PADDING_H = 48
        private const val DIALOG_PADDING_V = 24
        private const val PAUSA_300_MS = 300
        private const val PAUSA_600_MS = 600
        private const val PAUSA_900_MS = 900
        private const val PAUSA_1500_MS = 1500
    }
}
