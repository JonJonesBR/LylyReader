package com.jonjonesbr.audiobookgen.ui

import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.DOWNLOADS_SIMULTANEOS_MAX
import com.jonjonesbr.audiobookgen.domain.DOWNLOADS_SIMULTANEOS_MIN
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.domain.espacoSuficiente
import com.jonjonesbr.audiobookgen.service.AndamentoDownload
import com.jonjonesbr.audiobookgen.service.DownloadCentral
import com.jonjonesbr.audiobookgen.service.ItemBaixavel
import com.jonjonesbr.audiobookgen.util.NetworkStatus
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import kotlinx.coroutines.launch

/**
 * Central de downloads: todos os pacotes de voz (e codificadores) com tamanho, estado e espaço; por item
 * baixar/pausar/continuar/cancelar/apagar, "baixar selecionados" e "baixar tudo". O trabalho roda na
 * [DownloadCentral] (sobrevive à tela); aqui só se observa o andamento.
 */
class DownloadCentralActivity : BasePlayerActivity() {

    private lateinit var adapter: DownloadCentralAdapter
    private lateinit var btnSelecionados: Button
    private lateinit var btnTudo: Button
    private lateinit var btnPausarTudo: Button
    private lateinit var btnSimultaneos: Button

    private val prefs by lazy { AppPrefs(this) }
    private val selecionados = mutableSetOf<String>()
    private var itens: List<ItemBaixavel> = emptyList()
    private var filtro = com.jonjonesbr.audiobookgen.domain.FiltroIdioma.TODOS
    /** Primeiro acesso ao app: a Central em "modo escolha de vozes" (sem barra inferior; "Continuar" segue para a Biblioteca). */
    private var primeiroAcesso = false
    private var andamentoAtual: Map<String, AndamentoDownload> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_central)
        com.jonjonesbr.audiobookgen.data.AppPrefs(this).registrarEvento(com.jonjonesbr.audiobookgen.domain.EventoAprendizado.DOWNLOADS_ABERTO.id)
        configurarMiniPlayerBotoes()
        if (!intent.getBooleanExtra(EXTRA_PRIMEIRO_ACESSO, false)) NavegacaoPrincipal.configurar(this, AbaPrincipal.DOWNLOADS)
        findViewById<android.view.View>(R.id.layoutFooterDownloads).aplicarPaddingInferiorComNavigationBar()
        findViewById<ImageButton>(R.id.btnVoltarDownloads).setOnClickListener { finish() }
        btnSelecionados = findViewById(R.id.btnBaixarSelecionados)
        btnTudo = findViewById(R.id.btnBaixarTudo)
        btnPausarTudo = findViewById(R.id.btnPausarTudo)
        btnSimultaneos = findViewById(R.id.btnSimultaneos)
        primeiroAcesso = intent.getBooleanExtra(EXTRA_PRIMEIRO_ACESSO, false)
        // Oferecida uma vez: ao abrir já conta como vista (sair por qualquer caminho não a traz de volta).
        if (primeiroAcesso) prefs.voiceDownloadOnboardingDone = true
        configurarFiltroDeIdioma()
        if (primeiroAcesso) configurarPrimeiroAcesso()

        adapter = DownloadCentralAdapter(
            onPrincipal = ::aoToquePrincipal,
            onSecundario = ::aoToqueSecundario,
            onSelecionar = { id, marcado ->
                if (marcado) selecionados += id else selecionados -= id
                atualizarRodape()
            }
        )
        findViewById<RecyclerView>(R.id.recyclerDownloads).apply {
            layoutManager = LinearLayoutManager(this@DownloadCentralActivity)
            this.adapter = this@DownloadCentralActivity.adapter
            itemAnimator = null // a barra de progresso atualiza ~10x/s; animar cada troca pisca
        }

        btnSelecionados.setOnClickListener { baixar(selecionados.toList()) }
        btnTudo.setOnClickListener { baixarTudo() }
        btnPausarTudo.setOnClickListener { DownloadCentral.pausarTodos() }
        findViewById<Button>(R.id.btnBuscarLivrosDownloads).setOnClickListener {
            startActivity(android.content.Intent(this, BuscarLivrosActivity::class.java))
        }
        btnSimultaneos.setOnClickListener {
            val proximo = prefs.downloadsSimultaneos + 1
            prefs.downloadsSimultaneos =
                if (proximo > DOWNLOADS_SIMULTANEOS_MAX) DOWNLOADS_SIMULTANEOS_MIN else proximo
            atualizarCabecalho()
        }

        // Só em build de depuração: enfileira os pacotes do extra (ids separados por |) sem tocar na tela.
        val depuravel = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        intent.getStringExtra("extra_debug_baixar_ids")?.takeIf { depuravel }?.split("|")?.let { ids ->
            DownloadCentral.enfileirar(this, ids)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCentral.atualizar(applicationContext)
                itens = DownloadCentral.itens()
                var chavesAnteriores = emptySet<String>()
                DownloadCentral.andamento.collect { mapa ->
                    andamentoAtual = mapa
                    // Livros entram e saem da lista enquanto baixam: relê os itens quando o conjunto muda.
                    if (mapa.keys != chavesAnteriores) {
                        chavesAnteriores = mapa.keys
                        itens = DownloadCentral.itens()
                    }
                    montarLista()
                }
            }
        }
    }

    private fun configurarFiltroDeIdioma() {
        val grupo = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipsIdiomaDownloads)
        // Começa no idioma do app (pt/en/es); qualquer outro idioma começa em "Todos".
        filtro = com.jonjonesbr.audiobookgen.domain.IdiomaDownload.padraoDoIdioma(resources.configuration.locales[0].language)
        grupo.check(idDoChip(filtro))
        grupo.setOnCheckedStateChangeListener { _, ids ->
            filtro = when (ids.firstOrNull()) {
                R.id.chipIdiomaPt -> com.jonjonesbr.audiobookgen.domain.FiltroIdioma.PT
                R.id.chipIdiomaEn -> com.jonjonesbr.audiobookgen.domain.FiltroIdioma.EN
                R.id.chipIdiomaEs -> com.jonjonesbr.audiobookgen.domain.FiltroIdioma.ES
                else -> com.jonjonesbr.audiobookgen.domain.FiltroIdioma.TODOS
            }
            selecionados.retainAll(visiveis().map { it.id }.toSet())
            montarLista()
        }
    }

    private fun idDoChip(f: com.jonjonesbr.audiobookgen.domain.FiltroIdioma) = when (f) {
        com.jonjonesbr.audiobookgen.domain.FiltroIdioma.PT -> R.id.chipIdiomaPt
        com.jonjonesbr.audiobookgen.domain.FiltroIdioma.EN -> R.id.chipIdiomaEn
        com.jonjonesbr.audiobookgen.domain.FiltroIdioma.ES -> R.id.chipIdiomaEs
        com.jonjonesbr.audiobookgen.domain.FiltroIdioma.TODOS -> R.id.chipIdiomaTodos
    }

    /** Itens que o filtro de idioma mostra (no primeiro acesso, só os pacotes de voz). */
    private fun visiveis(): List<ItemBaixavel> = itens.filter { item ->
        com.jonjonesbr.audiobookgen.domain.IdiomaDownload.combina(filtro, item.idiomas) &&
            (!primeiroAcesso || item.grupo == com.jonjonesbr.audiobookgen.service.GrupoDownload.MOTORES)
    }

    private fun configurarPrimeiroAcesso() {
        findViewById<android.widget.TextView>(R.id.tvTituloDownloads).setText(R.string.dl_primeiro_titulo)
        findViewById<android.widget.TextView>(R.id.tvDescDownloads).setText(R.string.dl_primeiro_desc)
        findViewById<android.view.View>(R.id.btnBuscarLivrosDownloads).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.navPrincipal).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnConcluirPrimeiroAcesso).apply {
            visibility = android.view.View.VISIBLE
            setOnClickListener { concluirPrimeiroAcesso() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = concluirPrimeiroAcesso()
        })
    }

    private fun concluirPrimeiroAcesso() {
        prefs.voiceDownloadOnboardingDone = true
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!primeiroAcesso) NavegacaoPrincipal.configurar(this, AbaPrincipal.DOWNLOADS) // volta de outra tela: realça de novo a aba certa
        atualizarCabecalho()
    }

    private fun atualizarCabecalho() {
        findViewById<android.widget.TextView>(R.id.tvEspacoLivre).text = getString(
            R.string.dl_central_espaco, Formatter.formatShortFileSize(this, filesDir.usableSpace)
        )
        btnSimultaneos.text = getString(R.string.dl_central_simultaneos, prefs.downloadsSimultaneos)
    }

    private fun montarLista() {
        // Quem já entrou na fila / está baixando deixa a seleção.
        selecionados.removeAll { (andamentoAtual[it]?.estado ?: EstadoDownload.NAO_BAIXADO).let { e ->
            e != EstadoDownload.NAO_BAIXADO && e != EstadoDownload.PAUSADO && e != EstadoDownload.ERRO
        } }
        var grupoAnterior: Any? = null
        val linhas = visiveis().sortedBy { it.grupo.ordinal }.map { item ->
            val linha = LinhaDownload(
                item = item,
                andamento = andamentoAtual[item.id] ?: AndamentoDownload(),
                abreGrupo = item.grupo != grupoAnterior,
                selecionado = item.id in selecionados
            )
            grupoAnterior = item.grupo
            linha
        }
        adapter.submitList(linhas)
        atualizarRodape()
        findViewById<RecyclerView>(R.id.recyclerDownloads).post {
            CoachMark.mostrarProxima(this, com.jonjonesbr.audiobookgen.domain.TelaDica.DOWNLOADS) { _ ->
                findViewById<RecyclerView>(R.id.recyclerDownloads).getChildAt(0)
            }
        }
    }

    private fun atualizarRodape() {
        btnSelecionados.text = getString(R.string.dl_central_baixar_selecionados, selecionados.size)
        btnSelecionados.isEnabled = selecionados.isNotEmpty()
        val faltam = pendentes().size
        btnTudo.text = getString(R.string.dl_central_baixar_todos, faltam)
        btnTudo.isEnabled = faltam > 0
        btnPausarTudo.isEnabled = DownloadCentral.temAtividade()
    }

    /** Pacotes baixáveis que ainda não estão prontos nem em andamento. */
    private fun pendentes(): List<ItemBaixavel> = visiveis().filter { item ->
        item.download != null && (andamentoAtual[item.id]?.estado ?: EstadoDownload.NAO_BAIXADO).let {
            it == EstadoDownload.NAO_BAIXADO || it == EstadoDownload.PAUSADO || it == EstadoDownload.ERRO
        }
    }

    private fun aoToquePrincipal(linha: LinhaDownload) {
        when (linha.estado) {
            EstadoDownload.NA_FILA, EstadoDownload.BAIXANDO -> DownloadCentral.pausar(linha.item.id)
            EstadoDownload.NAO_BAIXADO, EstadoDownload.PAUSADO, EstadoDownload.ERRO -> baixar(listOf(linha.item.id))
            EstadoDownload.PRONTO -> Unit
        }
    }

    private fun aoToqueSecundario(linha: LinhaDownload) {
        val mensagem = if (linha.estado == EstadoDownload.PRONTO) R.string.dl_central_apagar_msg
        else R.string.dl_central_cancelar_msg
        AlertDialog.Builder(this)
            .setTitle(linha.item.nome)
            .setMessage(getString(mensagem, linha.item.nome))
            .setPositiveButton(
                if (linha.estado == EstadoDownload.PRONTO) R.string.dl_central_apagar else R.string.dl_central_cancelar
            ) { _, _ ->
                if (linha.estado == EstadoDownload.PRONTO) DownloadCentral.apagar(this, linha.item.id)
                else DownloadCentral.cancelar(this, linha.item.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun baixarTudo() {
        val alvo = pendentes()
        if (alvo.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.dl_central_nada_a_baixar, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        baixar(alvo.map { it.id }, confirmarSempre = true)
    }

    /**
     * Enfileira [ids]. Pede confirmação quando o conjunto é grande ("baixar tudo"), quando falta espaço
     * ou quando não está no Wi-Fi (dados móveis).
     */
    private fun baixar(ids: List<String>, confirmarSempre: Boolean = false) {
        if (ids.isEmpty()) return
        val alvo = itens.filter { it.id in ids }
        val totalBytes = alvo.sumOf { it.tamanhoMb.toLong() } * BYTES_POR_MB
        val livre = filesDir.usableSpace
        val semEspaco = !espacoSuficiente(livre, totalBytes)
        val semWifi = !NetworkStatus.isOnWifi(this)
        if (!confirmarSempre && !semEspaco && !semWifi) {
            iniciar(ids)
            return
        }
        val texto = buildString {
            append(getString(R.string.dl_central_confirmar_msg, alvo.size, Formatter.formatShortFileSize(this@DownloadCentralActivity, totalBytes)))
            if (semEspaco) append("\n\n").append(getString(R.string.dl_central_pouco_espaco, Formatter.formatShortFileSize(this@DownloadCentralActivity, livre)))
            if (semWifi) append("\n\n").append(getString(R.string.pacote_download_wifi_aviso))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dl_central_confirmar_titulo)
            .setMessage(texto)
            .setPositiveButton(R.string.dl_central_baixar) { _, _ -> iniciar(ids) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun iniciar(ids: List<String>) {
        selecionados.removeAll(ids.toSet())
        DownloadCentral.enfileirar(this, ids)
    }

    companion object {
        /** Abre a Central como escolha de vozes do primeiro acesso (ver [MeusLivrosActivity]). */
        const val EXTRA_PRIMEIRO_ACESSO = "extra_primeiro_acesso"
        private const val BYTES_POR_MB = 1024L * 1024L
    }
}
