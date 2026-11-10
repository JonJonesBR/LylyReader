package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TipoRegistroErro
import com.jonjonesbr.audiobookgen.domain.resumirArquivoDeErro
import com.jonjonesbr.audiobookgen.util.ThemePrefs
import com.jonjonesbr.audiobookgen.util.mostrarErro
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.marcarComoLink
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
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
    private val buffering: AjustesBufferingController by lazy { AjustesBufferingController(this) }
    private val ritmo: AjustesRitmoController by lazy { AjustesRitmoController(this) }
    private val qualidade: AjustesQualidadeController by lazy { AjustesQualidadeController(this) }
    private val pausas: AjustesPausasController by lazy { AjustesPausasController(this, pythonEngine) }
    private val paginas: AjustesPaginasController by lazy { AjustesPaginasController(this) }
    private val motor: AjustesMotorController by lazy {
        AjustesMotorController(this, pythonEngine, AjustesMotorController.Callbacks(
            abrirPaginaDoMotor = { paginas.abrir(R.id.pagMotor, R.string.aj_motor) },
        ))
    }
    private val ouvir: AjustesOuvirController by lazy {
        AjustesOuvirController(this, pythonEngine, AjustesOuvirController.Callbacks(
            aoRestaurarSom = { carregarConfigAtual(); naturalidade.carregar() },
        ))
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

    private val naturalidade by lazy { NaturalidadeVozUi(this) }

    // Vozes Offline (card generalizado por pacote — V6 T2.2)

    // Blacklist
    private lateinit var tvBlacklistInfo: TextView

    // Logs de Erro
    private lateinit var tvLogsErroInfo: TextView

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

        paginas.vincular()
        vincularViews()
        paginas.configurarAbas(intent.getIntExtra(EXTRA_ABA, intent.getIntExtra("extra_debug_aba", -1)))
        carregarConfigAtual()
        configurarListeners()
        vincularPermissoes()
        naturalidade.vincular()
        motor.focarChaveSePedido()
    }

    override fun abaPrincipal() = AbaPrincipal.AJUSTES
    override fun abasInternas(): com.google.android.material.tabs.TabLayout? = findViewById(R.id.tabsSettings)
    override fun arrasteLateralLigado() = !paginas.temPaginaAberta

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
        findViewById<ImageButton>(R.id.btnAjudaSettings)
            .setOnClickListener { HelpActivity.start(this, HelpTopico.CONFIGURACOES) }

        // Blacklist
        tvBlacklistInfo = findViewById(R.id.tvBlacklistInfo)

        // Logs de Erro
        tvLogsErroInfo = findViewById(R.id.tvLogsErroInfo)

        // Permissão de notificações
        configurarBotaoPermissaoNotificacao()

        configurarBotoesAjuda()

        // Versão do app
        val versao = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "v?"
        }
        findViewById<TextView>(R.id.tvVersao).text = versao
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

        motor.carregar()

        pausas.carregar()

        ritmo.carregar()

        qualidade.carregar()

        buffering.carregar()

        ouvir.carregar()

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

        motor.configurar()

        pausas.configurar()

        ritmo.configurar()
        qualidade.configurar()
        buffering.configurar()

        armazenamento.configurar()
    }

    /** Cada linha abre a sua escolha, diálogo ou página. */
    private fun configurarLinhas() {
        linha(R.id.rowMotor) { paginas.abrir(R.id.pagMotor, R.string.aj_motor) }
        linha(R.id.rowAvancado) { paginas.abrir(R.id.pagAvancado, R.string.aj_avancado_titulo) }
        linha(R.id.rowSobre) { paginas.abrir(R.id.pagSobre, R.string.aj_sobre) }
        linha(R.id.rowVozesAparelho) { startActivity(Intent(this, DownloadCentralActivity::class.java)) }
        linha(R.id.rowPronuncias) { PronunciationDialog.showManager(this) }
        linha(R.id.rowBlacklist) { blacklistDialog.mostrarDialogoBlacklist() }
        linha(R.id.rowEstatisticas) { abrirEstatisticas() }
        linha(R.id.rowPocketMaisRapido) { qualidade.alternarPocketMaisRapido() }
        linha(R.id.rowTimbre) { TimbreDialog.show(this, appPrefs) }
        linha(R.id.rowAutoRewind) { ouvir.escolherAutoRewind() }
        linha(R.id.rowPlayerConversao) { ouvir.escolherPlayerConversao() }
        linha(R.id.rowBackupFazer) { backup.escolherBackupAFazer() }
        linha(R.id.rowBackupRestaurar) { backup.restaurarBackup() }
        linha(R.id.rowLogsErro) { abrirLogsErro() }
        linha(R.id.rowAprenderApp) { startActivity(Intent(this, AprenderActivity::class.java)) }
        linha(R.id.rowPermissoesApp) { paginas.abrir(R.id.pagPermissoes, R.string.aj_permissoes_app) }
        linha(R.id.rowAbrirTutorial) { HelpActivity.start(this, HelpTopico.CONVERSAO) }
        linha(R.id.rowReverDicas) {
            appPrefs.dicasVistas = emptySet()
            Toast.makeText(this, R.string.aprender_dicas_reativadas, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRestaurarSom).setOnClickListener { ouvir.confirmarRestaurarSom() }
    }

    private fun linha(id: Int, acao: () -> Unit) {
        findViewById<View>(id).apply {
            marcarComoBotao()
            setOnClickListener { acao() }
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

    companion object {
        /** Aba inicial (0 = Voz e leitura, 1 = Som, 2 = Dados, 3 = Ajuda). */
        const val EXTRA_ABA = "extra_aba"
        const val ABA_SOM = 1
    }
}
