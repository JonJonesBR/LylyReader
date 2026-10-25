package com.jonjonesbr.audiobookgen.ui

import android.os.Bundle
import android.text.format.Formatter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aba Downloads: uma lista só, que rola inteira, com os filtros de idioma no começo e as seções Baixando
 * agora, No aparelho, Para baixar, Minhas vozes (com "Criar ou importar voz") e Livros. Cada item tem o seu
 * botão; "Baixar todas", downloads ao mesmo tempo e buscar livros ficam no ⋮. O trabalho roda na
 * [DownloadCentral] (sobrevive à tela); aqui só se observa o andamento.
 */
class DownloadCentralActivity : BasePlayerActivity() {

    private val dialogos = DialogosAbertos(this)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)

    private lateinit var adapter: DownloadCentralAdapter
    private lateinit var filtros: android.view.View

    private val prefs by lazy { AppPrefs(this) }
    private var itens: List<ItemBaixavel> = emptyList()
    private var filtro = com.jonjonesbr.audiobookgen.domain.IdiomaBusca.TODOS
    private var andamentoAtual: Map<String, AndamentoDownload> = emptyMap()
    private var chavesCoach: Set<String>? = null

    // "Criar ou importar voz": antes ficava em Ajustes › Áudio, longe das outras vozes.
    private val cloneVoiceFlow = CloneVoiceFlow(this) { recarregarItens() }
    private val importarVozLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>> =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importadorDeVoz.importarPacoteByom(it) }
        }
    private val importadorDeVoz: PacotesVozOfflineCard by lazy {
        PacotesVozOfflineCard(
            this,
            abrirSeletorByom = { importarVozLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            aoMudar = { recarregarItens() }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_central)
        com.jonjonesbr.audiobookgen.data.AppPrefs(this).registrarEvento(com.jonjonesbr.audiobookgen.domain.EventoAprendizado.DOWNLOADS_ABERTO.id)
        configurarMiniPlayerBotoes()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.DOWNLOADS)
        findViewById<ImageButton>(R.id.btnVoltarDownloads).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnMaisDownloads).setOnClickListener { mostrarMenuMais(it) }
        val recycler = findViewById<RecyclerView>(R.id.recyclerDownloads)
        // O LayoutManager vem antes de inflar os filtros com a lista como pai: sem ele, a RecyclerView
        // lança IllegalStateException ao gerar os LayoutParams (era o crash ao abrir a aba).
        recycler.layoutManager = LinearLayoutManager(this)
        filtros = layoutInflater.inflate(R.layout.view_downloads_filtros, recycler, false)
        configurarFiltroDeIdioma()

        adapter = DownloadCentralAdapter(
            onPrincipal = ::aoToquePrincipal,
            onSecundario = ::aoToqueSecundario,
            onPausarTudo = { DownloadCentral.pausarTodos() },
            onCriarVoz = ::mostrarCriarVoz
        )
        recycler.apply {
            this.adapter = androidx.recyclerview.widget.ConcatAdapter(
                CabecalhoFixoAdapter(filtros), this@DownloadCentralActivity.adapter
            )
            itemAnimator = null // a barra de progresso atualiza ~10x/s; animar cada troca pisca
        }

        // Só em build de depuração: enfileira os pacotes do extra (ids separados por |) sem tocar na tela.
        val depuravel = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        intent.getStringExtra("extra_debug_baixar_ids")?.takeIf { depuravel }?.split("|")?.let { ids ->
            DownloadCentral.enfileirar(this, ids)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadCentral.atualizar(applicationContext)
                itens = withContext(Dispatchers.IO) { DownloadCentral.itens(applicationContext) }
                var chavesAnteriores = emptySet<String>()
                DownloadCentral.andamento.collect { mapa ->
                    andamentoAtual = mapa
                    // Livros entram e saem da lista enquanto baixam: relê os itens quando o conjunto muda.
                    if (mapa.keys != chavesAnteriores) {
                        chavesAnteriores = mapa.keys
                        itens = withContext(Dispatchers.IO) { DownloadCentral.itens(applicationContext) }
                    }
                    montarLista()
                }
            }
        }
    }

    /** Clonar uma voz, ver as clonadas ou importar um pacote de voz (.zip). */
    private fun mostrarCriarVoz() {
        val opcoes = arrayOf(
            getString(R.string.dl_voz_clonar),
            getString(R.string.dl_voz_clonadas),
            getString(R.string.dl_voz_importar)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dl_voz_criar)
            .setItems(opcoes) { _, qual ->
                when (qual) {
                    0 -> cloneVoiceFlow.iniciar()
                    1 -> cloneVoiceFlow.gerenciar()
                    else -> importadorDeVoz.mostrarAvisoLicencaEImportar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** Relê os itens depois de criar, importar ou apagar uma voz fora da Central. */
    private fun recarregarItens() {
        lifecycleScope.launch {
            DownloadCentral.atualizar(applicationContext)
            itens = withContext(Dispatchers.IO) { DownloadCentral.itens(applicationContext) }
            montarLista()
        }
    }

    private fun configurarFiltroDeIdioma() {
        val grupo = filtros.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipsIdiomaDownloads)
        // Começa no idioma do app (pt/en/es); qualquer outro idioma começa em "Todos".
        filtro = com.jonjonesbr.audiobookgen.domain.IdiomaDownload.padraoDoIdioma(resources.configuration.locales[0].language)
        grupo.check(idDoChip(filtro))
        grupo.setOnCheckedStateChangeListener { _, ids ->
            filtro = when (ids.firstOrNull()) {
                R.id.chipIdiomaPt -> com.jonjonesbr.audiobookgen.domain.IdiomaBusca.PT
                R.id.chipIdiomaEn -> com.jonjonesbr.audiobookgen.domain.IdiomaBusca.EN
                R.id.chipIdiomaEs -> com.jonjonesbr.audiobookgen.domain.IdiomaBusca.ES
                else -> com.jonjonesbr.audiobookgen.domain.IdiomaBusca.TODOS
            }
            montarLista()
        }
    }

    private fun idDoChip(f: com.jonjonesbr.audiobookgen.domain.IdiomaBusca) = when (f) {
        com.jonjonesbr.audiobookgen.domain.IdiomaBusca.PT -> R.id.chipIdiomaPt
        com.jonjonesbr.audiobookgen.domain.IdiomaBusca.EN -> R.id.chipIdiomaEn
        com.jonjonesbr.audiobookgen.domain.IdiomaBusca.ES -> R.id.chipIdiomaEs
        com.jonjonesbr.audiobookgen.domain.IdiomaBusca.TODOS -> R.id.chipIdiomaTodos
    }

    /** Itens que o filtro de idioma mostra. */
    private fun visiveis(): List<ItemBaixavel> = itens.filter { item ->
        com.jonjonesbr.audiobookgen.domain.IdiomaDownload.combina(filtro, item.idiomas)
    }

    override fun onResume() {
        super.onResume()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.DOWNLOADS) // volta de outra tela: realça de novo a aba certa
        atualizarCabecalho()
    }

    private fun atualizarCabecalho() {
        findViewById<android.widget.TextView>(R.id.tvEspacoLivre).text = getString(
            R.string.dl_espaco_livre, Formatter.formatShortFileSize(this, filesDir.usableSpace)
        )
    }

    /** ⋮: baixar todas as vozes do filtro, quantos downloads ao mesmo tempo e buscar livros gratuitos. */
    private fun mostrarMenuMais(ancora: android.view.View) {
        android.widget.PopupMenu(this, ancora).apply {
            menu.add(0, MENU_BAIXAR_TODAS, 0, R.string.dl_menu_baixar_todas)
            menu.add(0, MENU_SIMULTANEOS, 1, getString(R.string.dl_menu_simultaneos, prefs.downloadsSimultaneos))
            menu.add(0, MENU_BUSCAR_LIVROS, 2, R.string.dl_buscar_livros)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_BAIXAR_TODAS -> baixarTudo()
                    MENU_SIMULTANEOS -> escolherSimultaneos()
                    MENU_BUSCAR_LIVROS -> startActivity(
                        android.content.Intent(this@DownloadCentralActivity, BuscarLivrosActivity::class.java)
                    )
                }
                true
            }
        }.show()
    }

    private fun escolherSimultaneos() {
        val opcoes = (DOWNLOADS_SIMULTANEOS_MIN..DOWNLOADS_SIMULTANEOS_MAX).toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.dl_simultaneos_titulo)
            .setSingleChoiceItems(
                opcoes.map { it.toString() }.toTypedArray(), opcoes.indexOf(prefs.downloadsSimultaneos)
            ) { dialogo, qual ->
                prefs.downloadsSimultaneos = opcoes[qual]
                dialogo.dismiss()
            }
            .exibir()
    }

    private fun montarLista() {
        val linhas = visiveis().map { item -> LinhaDownload(item, andamentoAtual[item.id] ?: AndamentoDownload()) }
        val lista = LinhaLista.montar(linhas, DownloadCentral.temAtividade())
        adapter.submitList(lista)
        // O coach mark só é avaliado no primeiro render e quando os itens exibidos mudam (não a cada tick de progresso).
        val chaves = linhas.map { it.item.id }.toSet()
        if (chaves != chavesCoach) {
            chavesCoach = chaves
            // +1: a primeira linha da lista são os filtros de idioma.
            val posicaoPrimeiroItem = lista.indexOfFirst { it is LinhaLista.Item } + 1
            val recycler = findViewById<RecyclerView>(R.id.recyclerDownloads)
            recycler.post {
                CoachMark.mostrarProxima(this, com.jonjonesbr.audiobookgen.domain.TelaDica.DOWNLOADS) { _ ->
                    recycler.layoutManager?.findViewByPosition(posicaoPrimeiroItem)
                }
            }
        }
    }

    /** Pacotes baixáveis que ainda não estão prontos nem em andamento. */
    private fun pendentes(): List<ItemBaixavel> = visiveis().filter { item ->
        val andamento = andamentoAtual[item.id] ?: AndamentoDownload()
        item.download != null && andamento.bloqueio == null && andamento.estado.let {
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
        val apagando = linha.estado == EstadoDownload.PRONTO || linha.apagavelSemEstarPronta
        val mensagem = if (apagando) R.string.dl_central_apagar_msg
        else R.string.dl_central_cancelar_msg
        AlertDialog.Builder(this)
            .setTitle(nomeExibidoDoPacote(this, linha.item.id, linha.item.nome))
            .setMessage(getString(mensagem, nomeExibidoDoPacote(this, linha.item.id, linha.item.nome)))
            .setPositiveButton(
                if (apagando) R.string.dl_central_apagar else R.string.dl_central_descartar
            ) { _, _ ->
                if (apagando) DownloadCentral.apagar(this, linha.item.id)
                else DownloadCentral.cancelar(this, linha.item.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
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
            .exibir()
    }

    private fun iniciar(ids: List<String>) {
        DownloadCentral.enfileirar(this, ids)
    }

    companion object {
        private const val BYTES_POR_MB = 1024L * 1024L
        private const val MENU_BAIXAR_TODAS = 1
        private const val MENU_SIMULTANEOS = 2
        private const val MENU_BUSCAR_LIVROS = 3
    }
}
