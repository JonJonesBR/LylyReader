package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.DocumentRepository
import com.jonjonesbr.audiobookgen.data.LinkedBookStore
import com.jonjonesbr.audiobookgen.domain.AudiobookLibraryUseCase
import com.jonjonesbr.audiobookgen.domain.CoverArtUseCase
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.domain.ImportedFileNamePolicy
import com.jonjonesbr.audiobookgen.domain.DURACAO_MAX_PARTE_PADRAO_MIN
import com.jonjonesbr.audiobookgen.domain.ModoDivisao
import com.jonjonesbr.audiobookgen.domain.OpcoesVideo
import com.jonjonesbr.audiobookgen.domain.ResolucaoVideo
import com.jonjonesbr.audiobookgen.util.dpToPx
import com.jonjonesbr.audiobookgen.util.limitarLarguraEmTelaAmpla
import com.jonjonesbr.audiobookgen.service.ChapterMarksStore
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.service.EstadoVideoExport
import com.jonjonesbr.audiobookgen.service.PedidoVideo
import com.jonjonesbr.audiobookgen.service.PlaybackProgressStore
import com.jonjonesbr.audiobookgen.service.VideoExportBridge
import com.jonjonesbr.audiobookgen.service.VideoExportService
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Button
import android.widget.ProgressBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class LibraryActivity : BasePlayerActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NOME = "nome"
        private const val TAG = "LibraryActivity"
        private const val COMPLETED_THRESHOLD = 95
        private const val MS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val DIALOG_PADDING_DP = 24
        private const val DIALOG_PADDING_TOP_DP = 20
        private const val DIALOG_TEXT_MARGIN_BOTTOM_DP = 16
        private const val DIALOG_TEXT_SIZE_SP = 15f
        private const val PROGRESS_MAX = 100
        private const val MIN_PARTE_VIDEO_MIN = 10
        private const val MAX_PARTE_VIDEO_MIN = 11 * 60 + 59
        private const val BATERIA_BAIXA_PCT = 20
        private const val SECONDS_PER_HOUR = 3600L
        private const val SORT_MODES_COUNT = 3
        private const val SORT_MODE_DATE = 0
        private const val SORT_MODE_NAME = 1
        private const val SORT_MODE_PROGRESS = 2
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutVazio: LinearLayout
    private lateinit var btnOrdenar: ImageButton
    private lateinit var tvOrdenar: TextView
    private var progressBarLibrary: ProgressBar? = null

    private var sortMode = SORT_MODE_DATE
    private var filtroBusca = ""
    private val audiobooks = mutableListOf<AudiobookItem>()
    private lateinit var adapter: AudiobookAdapter
    private var cargaAudiobooksJob: Job? = null

    // "Abrir arquivo de áudio" morava solto na tela inicial e só tocava o arquivo sem
    // guardá-lo em lugar nenhum — movido pra cá e agora importa (copia) pra biblioteca de
    // verdade, igual a um audiobook gerado pelo app.
    private val importarAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importarArquivosDeAudio(uris)
        }

    // Sem essa permissão o MediaStore só devolve arquivos que o app "possui" no momento —
    // pedida aqui (não só no onboarding) porque instalações já existentes, atualizando pra
    // essa versão, nunca passam pelo onboarding de novo.
    private val permissaoMidia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val solicitarPermissaoMidia =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { carregarAudiobooks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        configurarMiniPlayerBotoes()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.AUDIOBOOKS)
        observarExportacaoVideo()

        recyclerView = findViewById(R.id.recyclerViewLibrary)
        layoutVazio = findViewById(R.id.layoutVazio)
        recyclerView.limitarLarguraEmTelaAmpla(840)
        layoutVazio.limitarLarguraEmTelaAmpla(840)
        findViewById<View>(R.id.etBuscaLibrary).limitarLarguraEmTelaAmpla(840)
        findViewById<View>(R.id.continuarLendoSection).limitarLarguraEmTelaAmpla(840)
        btnOrdenar = findViewById(R.id.btnOrdenar)
        tvOrdenar = findViewById(R.id.tvOrdenar)
        progressBarLibrary = findViewById<ProgressBar?>(R.id.progressBarLibrary)

        findViewById<ImageButton>(R.id.btnVoltarLibrary).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAjudaLibrary)
            .setOnClickListener { HelpActivity.start(this, HelpTopico.BIBLIOTECA) }
        findViewById<ImageButton>(R.id.btnStatsLibrary)
            .setOnClickListener { mostrarEstatisticas() }
        findViewById<ImageButton>(R.id.btnImportarAudioLibrary)
            .setOnClickListener { importarAudio.launch(arrayOf("audio/*")) }
        findViewById<Button>(R.id.btnPermissaoVazio).setOnClickListener {
            // Re-pede a permissão de mídia (mesmo launcher usado no primeiro acesso); ao
            // conceder, o callback recarrega a lista e o vazio com hint some.
            solicitarPermissaoMidia.launch(permissaoMidia)
        }

        btnOrdenar.setOnClickListener {
            sortMode = (sortMode + 1) % SORT_MODES_COUNT
            tvOrdenar.text = getString(
                when (sortMode) {
                    SORT_MODE_DATE -> R.string.label_ordenar_data
                    SORT_MODE_NAME -> R.string.label_ordenar_nome
                    SORT_MODE_PROGRESS -> R.string.ordenar_nao_concluidos_primeiro
                    else -> R.string.label_ordenar_data
                }
            )
            aplicarOrdenacao()
        }

        adapter = AudiobookAdapter(
            onOuvir = { item -> abrirNoPlayer(item) },
            onCompartilhar = { item -> compartilhar(item) },
            onDeletar = { item -> confirmarDelecao(item) },
            onExportarVideo = { item -> confirmarExportarVideo(item) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        configurarCapas()

        findViewById<EditText>(R.id.etBuscaLibrary).addTextChangedListener { texto ->
            filtroBusca = texto?.toString()?.trim().orEmpty()
            aplicarOrdenacao()
        }
    }

    override fun onDestroy() {
        dialogoVideo?.dialog?.dismiss()
        dialogoVideo = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.AUDIOBOOKS) // volta de outra tela: realça de novo a aba certa
        atualizarRecentes()
        // Recarrega a cada vez que a tela volta a ficar visível (não só na criação): a pasta
        // padrão é pública (Downloads), então arquivos podem sumir por fora do app (ex.:
        // limpeza automática do sistema/OEM) ou surgir de uma conversão feita nesse meio-tempo.
        carregarAudiobooks()
    }

    private fun atualizarRecentes() {
        RecentReadingsUi.attach(this, max = 4)
    }

    private fun configurarCapas() {
        val coverUseCase = CoverArtUseCase(this)
        adapter.onBindCover = { item, imageView ->
            val caminho = item.caminho
            if (caminho != null) {
                imageView.tag = caminho
                lifecycleScope.launch {
                    val arquivo = withContext(Dispatchers.IO) {
                        coverUseCase.extrairOuGerarCapa(
                            caminhoLivro = null,
                            titulo = item.nome,
                            autor = "LylyReader"
                        )
                    }
                    val bitmap = arquivo?.let { coverUseCase.carregarBitmap(it) }
                    if (bitmap != null && imageView.tag == caminho) {
                        adapter.cacheCover(caminho, bitmap)
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun carregarAudiobooks() {
        // Cancela uma carga anterior ainda em andamento: onResume() roda de novo toda vez que
        // o dialogo de permissao do sistema fecha (pedido pelo botao do estado vazio), o que
        // podia empilhar duas corrotinas concorrentes — cada uma fazendo seu proprio
        // clear()+addAll() em momentos diferentes, duplicando os itens da lista.
        cargaAudiobooksJob?.cancel()
        progressBarLibrary?.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        layoutVazio.visibility = View.GONE

        cargaAudiobooksJob = lifecycleScope.launch {
            val loaded = AudiobookLibraryUseCase.listar(this@LibraryActivity)
            progressBarLibrary?.visibility = View.GONE
            audiobooks.clear()
            audiobooks.addAll(loaded)
            aplicarOrdenacao()
        }
    }

    /** Copia cada [uris] (arquivo de áudio externo) pra pasta de destino configurada (mesma
     * usada pelas conversões — SAF ou Downloads), como um audiobook novo na biblioteca. */
    private fun importarArquivosDeAudio(uris: List<Uri>) {
        Toast.makeText(this, R.string.toast_importando_audio, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val pastaDestino = AppPrefs(this@LibraryActivity).pastaDestino?.let { Uri.parse(it) }
            val exportUseCase = DocumentExportUseCase(this@LibraryActivity)
            val documentRepository = DocumentRepository(this@LibraryActivity)
            var importados = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    if (importarUmArquivoDeAudio(uri, pastaDestino, exportUseCase, documentRepository)) importados++
                }
            }
            if (importados > 0) {
                val msg = if (importados == 1) getString(R.string.toast_audio_importado)
                    else getString(R.string.toast_audios_importados, importados)
                Toast.makeText(this@LibraryActivity, msg, Toast.LENGTH_SHORT).show()
                carregarAudiobooks()
            }
        }
    }

    private suspend fun importarUmArquivoDeAudio(
        uri: Uri,
        pastaDestino: Uri?,
        exportUseCase: DocumentExportUseCase,
        documentRepository: DocumentRepository
    ): Boolean {
        val nome = documentRepository.extrairMetadadosArquivo(uri).nome
        val nomeSeguro = ImportedFileNamePolicy.safeCacheName(
            nome, uri.lastPathSegment ?: "audio", contentResolver.getType(uri)
        )
        val arquivoLocal = File(cacheDir, nomeSeguro)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                arquivoLocal.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            exportUseCase.exportar(arquivoLocal, pastaDestino).isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao importar $nome: ${e.message}")
            false
        } finally {
            arquivoLocal.delete()
        }
    }

    private fun aplicarOrdenacao() {
        val filtrado = if (filtroBusca.isBlank()) audiobooks
            else audiobooks.filter { it.nome.contains(filtroBusca, ignoreCase = true) }
        val ordenado = when (sortMode) {
            SORT_MODE_NAME -> filtrado.sortedBy { it.nome.lowercase() }
            SORT_MODE_DATE -> filtrado.sortedByDescending { it.dataAdicionado }
            SORT_MODE_PROGRESS -> filtrado.sortedWith(
                compareBy<AudiobookItem> { it.progressoPct >= COMPLETED_THRESHOLD }
                    .thenByDescending { it.dataAdicionado }
            )
            else -> filtrado
        }
        adapter.submitList(ordenado.toList())

        val vazio = ordenado.isEmpty()
        layoutVazio.visibility    = if (vazio) View.VISIBLE else View.GONE
        recyclerView.visibility   = if (vazio) View.GONE    else View.VISIBLE
        if (vazio) {
            val tvMsgVazio    = findViewById<TextView>(R.id.tvMsgVazio)
            val tvSubMsgVazio = findViewById<TextView>(R.id.tvSubMsgVazio)
            val btnPermissao  = findViewById<Button>(R.id.btnPermissaoVazio)
            // Gap P2 (confirmado em device): com READ_MEDIA_AUDIO negada, a query do MediaStore
            // lança SecurityException engolida → biblioteca vazia SEM explicar o motivo. Aqui a
            // mensagem vira um hint de permissão com ação, em vez do vazio genérico.
            val bibliotecaVaziaDeVerdade = filtroBusca.isBlank() || audiobooks.isEmpty()
            if (bibliotecaVaziaDeVerdade && !temPermissaoMidia()) {
                tvMsgVazio.setText(R.string.library_perm_necessaria)
                tvSubMsgVazio.visibility = View.GONE
                btnPermissao.visibility  = View.VISIBLE
            } else {
                // Distingue "biblioteca vazia" de "busca sem resultados".
                tvMsgVazio.setText(
                    if (filtroBusca.isNotBlank() && audiobooks.isNotEmpty())
                        R.string.library_search_no_results
                    else R.string.msg_nenhum_audiobook
                )
                tvSubMsgVazio.visibility = View.VISIBLE
                btnPermissao.visibility  = View.GONE
            }
        }
    }

    private fun temPermissaoMidia(): Boolean =
        ContextCompat.checkSelfPermission(this, permissaoMidia) == PackageManager.PERMISSION_GRANTED

    private fun abrirNoPlayer(item: AudiobookItem) {
        // Aberta como aba (ninguém espera resultado): toca aqui mesmo; o mini-player aparece na base.
        if (callingActivity == null) {
            val intent = Intent(this, com.jonjonesbr.audiobookgen.service.AudioPlayerService::class.java).apply {
                action = com.jonjonesbr.audiobookgen.service.AudioPlayerService.ACTION_INICIAR
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_CAMINHO, item.uri.toString())
                putExtra(com.jonjonesbr.audiobookgen.service.AudioPlayerService.EXTRA_NOME, item.nome)
            }
            ContextCompat.startForegroundService(this, intent)
            return
        }
        val intent = Intent().apply {
            putExtra(EXTRA_URI, item.uri.toString())
            putExtra(EXTRA_NOME, item.nome)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun compartilhar(item: AudiobookItem) {
        val shareUri = try {
            if (item.caminho != null) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(item.caminho))
            } else {
                item.uri
            }
        } catch (_: Exception) { item.uri }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_audiobook_chooser)))
    }

    private fun confirmarDelecao(item: AudiobookItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_audiobook_title)
            .setMessage(getString(R.string.dialog_delete_audiobook_message, item.nome))
            .setPositiveButton(R.string.dialog_delete_audiobook_confirm) { _, _ -> deletar(item) }
            .setNegativeButton(R.string.btn_cancelar, null)
            .show()
    }

    private fun deletar(item: AudiobookItem) {
        try {
            val deleted = if (item.caminho != null) {
                File(item.caminho).delete()
            } else {
                contentResolver.delete(item.uri, null, null) > 0
            }
            if (deleted) {
                val chave = item.uri.toString()
                PlaybackProgressStore.clear(this, chave)
                ChapterMarksStore.remover(this, chave)
                GuidedBookPrefsStore.remover(this, chave)
                LinkedBookStore.removerPorMp3(this, chave)
                audiobooks.removeAll { it.uri == item.uri }
                aplicarOrdenacao()
                Toast.makeText(this, getString(R.string.toast_audiobook_deleted, item.nome), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_audiobook_delete_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao deletar: ${e.message}")
            Toast.makeText(
                this,
                getString(R.string.toast_audiobook_delete_error, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Exportar como vídeo (MP4) — experimental, sem ffmpeg (MediaCodec/MediaMuxer) ─────

    private fun confirmarExportarVideo(item: AudiobookItem) {
        val resolucoes = ResolucaoVideo.values()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(DIALOG_PADDING_DP), dpToPx(DIALOG_PADDING_TOP_DP),
                dpToPx(DIALOG_PADDING_DP), dpToPx(DIALOG_PADDING_DP)
            )
        }
        fun rotulo(res: Int) = TextView(this).apply {
            setText(res)
            textSize = DIALOG_TEXT_SIZE_SP
            setPadding(0, dpToPx(DIALOG_TEXT_MARGIN_BOTTOM_DP), 0, 0)
        }
        val mensagem = TextView(this).apply {
            text = getString(R.string.dialog_export_video_message) +
                if (bateriaBaixaSemCarregar()) "\n\n" + getString(R.string.video_aviso_bateria) else ""
            textSize = DIALOG_TEXT_SIZE_SP
        }
        val spinnerResolucao = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LibraryActivity, android.R.layout.simple_spinner_dropdown_item,
                resolucoes.map { "${it.altura}p" }
            )
            setSelection(resolucoes.indexOf(ResolucaoVideo.P240))
        }
        val campoDuracao = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DURACAO_MAX_PARTE_PADRAO_MIN.toString())
        }
        val modos = ModoDivisao.values()
        val spinnerModo = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LibraryActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.video_modo_maximo), getString(R.string.video_modo_iguais))
            )
        }
        container.addView(mensagem)
        container.addView(rotulo(R.string.video_opt_resolucao))
        container.addView(spinnerResolucao)
        container.addView(rotulo(R.string.video_opt_duracao_max))
        container.addView(campoDuracao)
        container.addView(rotulo(R.string.video_opt_modo))
        container.addView(spinnerModo)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_export_video_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_export_video_confirm) { _, _ ->
                val minutos = campoDuracao.text.toString().toIntOrNull()
                    ?.coerceIn(MIN_PARTE_VIDEO_MIN, MAX_PARTE_VIDEO_MIN) ?: DURACAO_MAX_PARTE_PADRAO_MIN
                val opcoes = OpcoesVideo(
                    resolucao = resolucoes[spinnerResolucao.selectedItemPosition],
                    duracaoMaxParteMin = minutos,
                    modo = modos[spinnerModo.selectedItemPosition]
                )
                exportarComoVideo(item, opcoes)
            }
            .setNegativeButton(R.string.btn_cancelar, null)
            .show()
    }

    private data class DialogoProgressoVideo(val dialog: AlertDialog, val texto: TextView, val barra: ProgressBar)

    private var dialogoVideo: DialogoProgressoVideo? = null

    private fun exportarComoVideo(item: AudiobookItem, opcoes: OpcoesVideo) {
        if (VideoExportBridge.state.value?.terminou == false) return // já há uma exportação em andamento
        VideoExportBridge.pedido = PedidoVideo(item.uri, item.caminho, item.nome, item.duracaoMs, opcoes)
        VideoExportBridge.state.value = EstadoVideoExport(item.nome)
        VideoExportService.start(this)
    }

    /** Mostra o andamento da exportação (que roda no serviço) enquanto a tela está visível. */
    private fun observarExportacaoVideo() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VideoExportBridge.state.collect { estado ->
                    when {
                        estado == null -> fecharDialogoVideo()
                        estado.terminou -> {
                            fecharDialogoVideo()
                            mostrarResultadoExportacaoVideo(estado)
                            VideoExportBridge.state.value = null
                        }
                        else -> atualizarDialogoVideo(estado)
                    }
                }
            }
        }
    }

    private fun atualizarDialogoVideo(estado: EstadoVideoExport) {
        val dialogo = dialogoVideo ?: criarDialogoProgressoVideo().also {
            dialogoVideo = it
            it.dialog.show()
        }
        dialogo.barra.progress = estado.pct
        dialogo.texto.text = getString(R.string.status_exportando_video_parte, estado.parte, estado.pct)
    }

    private fun fecharDialogoVideo() {
        dialogoVideo?.dialog?.dismiss()
        dialogoVideo = null
    }

    private fun mostrarResultadoExportacaoVideo(estado: EstadoVideoExport) {
        val partes = estado.partesExportadas
        when {
            partes == 1 -> Toast.makeText(this, R.string.toast_video_exportado, Toast.LENGTH_LONG).show()
            partes != null -> Toast.makeText(
                this, getString(R.string.toast_video_exportado_partes, partes), Toast.LENGTH_LONG
            ).show()
            estado.cancelado -> Toast.makeText(this, R.string.toast_video_cancelado, Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(
                this, getString(R.string.toast_erro_exportar_video, estado.erro.orEmpty()), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun criarDialogoProgressoVideo(): DialogoProgressoVideo {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dpToPx(DIALOG_PADDING_DP), dpToPx(DIALOG_PADDING_TOP_DP),
                dpToPx(DIALOG_PADDING_DP), dpToPx(DIALOG_PADDING_DP)
            )
        }
        val texto = TextView(this).apply {
            text = getString(R.string.status_exportando_video, 0)
            textSize = DIALOG_TEXT_SIZE_SP
            setPadding(0, 0, 0, dpToPx(DIALOG_TEXT_MARGIN_BOTTOM_DP))
        }
        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = 0
        }
        container.addView(texto)
        container.addView(barra)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_downloading_title)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancelar) { _, _ -> VideoExportBridge.cancelar?.invoke() }
            .create()
        return DialogoProgressoVideo(dialog, texto, barra)
    }

    private fun bateriaBaixaSemCarregar(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return nivel in 1..BATERIA_BAIXA_PCT && !bm.isCharging
    }

    private fun mostrarEstatisticas() {
        val total = audiobooks.size
        val duracaoTotalMs = audiobooks.sumOf { it.duracaoMs }
        val concluidos = audiobooks.count { it.progressoPct >= COMPLETED_THRESHOLD }
        val emAndamento = audiobooks.count {
            it.progressoPct in 1 until COMPLETED_THRESHOLD
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.stats_dialog_title)
            .setMessage(
                getString(
                    R.string.stats_dialog_message,
                    total,
                    formatarDuracao(duracaoTotalMs),
                    concluidos,
                    emAndamento
                )
            )
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun formatarDuracao(ms: Long): String {
        val totalSeg = ms / MS_PER_SECOND
        val horas = totalSeg / SECONDS_PER_HOUR
        val minutos = (totalSeg % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return when {
            horas > 0 -> getString(R.string.stats_tempo_horas_minutos, horas, minutos)
            minutos > 0 -> getString(R.string.stats_tempo_minutos, minutos)
            else -> getString(R.string.stats_tempo_segundos, totalSeg)
        }
    }
}
