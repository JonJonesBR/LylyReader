package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.nomeNormalizadoParaDedupe
import com.jonjonesbr.audiobookgen.domain.AudiobookLibraryUseCase
import com.jonjonesbr.audiobookgen.domain.EventoAprendizado
import com.jonjonesbr.audiobookgen.domain.ExportStatus
import com.jonjonesbr.audiobookgen.service.ConversionQueueCoordinator
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import com.jonjonesbr.audiobookgen.service.QueueManager
import com.jonjonesbr.audiobookgen.util.NetworkStatus
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Tela "Conversões": o que está sendo gerado agora, a fila de processamento, o resultado e atalhos para os audiobooks.
 * Substitui a tela antiga de conversão: a conversão roda no WorkManager e aqui só se acompanha; a fila também roda aqui.
 * Ver Fase P do plano.
 */
class ConversoesActivity : AppCompatActivity() {

    private val viewModel: AudiobookViewModel by viewModels()
    private var tituloDoLivro: String = ""
    /** Resultado de uma conversão da fila (o evento do ViewModel já foi consumido para a fila seguir). */
    private var resultadoLocal: ConversionDoneEvent? = null
    private var eventoRegistrado = false

    private val prefsLegado get() = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)

    private lateinit var cardAgora: View
    private lateinit var cardPronto: View
    private lateinit var cardErro: View
    private lateinit var layoutVazio: View
    private lateinit var cardFila: View

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversoes)

        cardAgora = findViewById(R.id.cardConvAgora)
        cardPronto = findViewById(R.id.cardConvPronto)
        cardErro = findViewById(R.id.cardConvErro)
        layoutVazio = findViewById(R.id.layoutConvVazio)
        cardFila = findViewById(R.id.cardConvFila)

        tituloDoLivro = savedInstanceState?.getString(ESTADO_TITULO) ?: intent.getStringExtra(EXTRA_TITULO).orEmpty()

        // O destino do arquivo gerado é o escolhido nas preferências (pasta própria ou Downloads).
        viewModel.setPastaDestino(AppPrefs(this).pastaDestino?.let { Uri.parse(it) })

        findViewById<ImageButton>(R.id.btnVoltarConv).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnConvCancelar).setOnClickListener { confirmarCancelamento() }
        findViewById<Button>(R.id.btnConvFecharPronto).setOnClickListener { fecharResultado() }
        findViewById<Button>(R.id.btnConvFecharErro).setOnClickListener { fecharResultado() }
        findViewById<Button>(R.id.btnConvIrBiblioteca).setOnClickListener { irParaAba(MeusLivrosActivity::class.java) }
        findViewById<Button>(R.id.btnConvVerAudiobooks).setOnClickListener { irParaAba(LibraryActivity::class.java) }
        findViewById<Button>(R.id.btnConvVerFila).setOnClickListener { startActivity(Intent(this, QueueActivity::class.java)) }
        findViewById<Button>(R.id.btnConvIniciarFila).setOnClickListener { rodarFila() }

        // Sem repeatOnLifecycle de propósito: a fila precisa seguir para o próximo item mesmo com a tela apagada.
        lifecycleScope.launch { viewModel.uiState.collect { mostrar(it) } }

        lifecycleScope.launch {
            carregarFila()
            atualizarFila()
            acompanharConversaoEmAndamento()
            if (savedInstanceState != null) return@launch
            val arquivo = intent.getStringExtra(EXTRA_ARQUIVO) ?: intent.getStringExtra(EXTRA_LIVRO_CAMINHO)
            // Só em build de depuração: enfileira os arquivos do extra (separados por |) e inicia a fila, sem tocar na tela.
            val depuravel = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            intent.getStringExtra(EXTRA_DEBUG_ENFILEIRAR)?.takeIf { depuravel }?.split("|")?.forEach {
                QueueManager.adicionar(File(it).name, it)
            }
            if (depuravel && intent.hasExtra(EXTRA_DEBUG_ENFILEIRAR)) salvarFila()
            when {
                intent.hasExtra(EXTRA_DEBUG_ENFILEIRAR) && depuravel -> rodarFila()
                intent.getBooleanExtra(EXTRA_INICIAR_FILA, false) -> rodarFila()
                arquivo != null -> iniciarPedido(arquivo)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_TITULO, tituloDoLivro)
    }

    /** Reabriu a tela com uma conversão (avulsa ou da fila) rodando no worker: volta a acompanhá-la. */
    private suspend fun acompanharConversaoEmAndamento() {
        if (viewModel.uiState.value.processando || !jaHaConversao()) return
        coordenador.sincronizar()
        coordenador.currentItem?.let { tituloDoLivro = it.nome }
        viewModel.acompanharTrabalhoAtual(coordenador.currentItem?.runToken)
    }

    // ── Início da conversão ──────────────────────────────────────────────────

    private fun iniciarPedido(arquivo: String) {
        val caminhoDoLivro = intent.getStringExtra(EXTRA_LIVRO_CAMINHO)
        val livroInteiro = intent.getBooleanExtra(EXTRA_LIVRO_INTEIRO, intent.getStringExtra(EXTRA_ARQUIVO) == null)
        val nome = intent.getStringExtra(EXTRA_NOME) ?: File(arquivo).nameWithoutExtension
        val voz = intent.getStringExtra(EXTRA_VOZ)
        val motor = intent.getStringExtra(EXTRA_MOTOR)
        val ritmo = intent.getIntExtra(EXTRA_RITMO, SEM_RITMO).takeIf { it != SEM_RITMO }
        val estilo = intent.getStringExtra(EXTRA_ESTILO) ?: "padrao"
        lifecycleScope.launch {
            if (jaHaConversao()) {
                Toast.makeText(this@ConversoesActivity, R.string.conv_ja_andando, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (bloqueadoPorFaltaDeRede(caminhoDoLivro, voz, motor, ritmo)) return@launch
            if (jaExisteAudiobook(nome) && !confirmarReconversao(nome)) return@launch
            resultadoLocal = null
            viewModel.pararPlayer()
            viewModel.iniciarConversao(arquivo, nome, caminhoDoLivro, livroInteiro, estilo, voz, motor, ritmo)
        }
    }

    private fun bloqueadoPorFaltaDeRede(caminhoDoLivro: String?, voz: String?, motor: String?, ritmo: Int?): Boolean {
        val ajustes = viewModel.ajustesDoLivro(caminhoDoLivro, voz, motor, ritmo)
        val motorEfetivo = VoiceCatalog.effectiveEngine(ajustes.voz, ajustes.motor)
        val semRede = NetworkStatus.engineRequiresNetwork(motorEfetivo) && !NetworkStatus.isOnline(applicationContext)
        if (semRede) {
            AlertDialog.Builder(this)
                .setTitle(R.string.offline_titulo)
                .setMessage(R.string.offline_mensagem)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }
        return semRede
    }

    private suspend fun jaHaConversao(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(ConversionWorker.WORK_NAME).get()
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }.getOrDefault(false)
    }

    private suspend fun jaExisteAudiobook(nome: String): Boolean = withContext(Dispatchers.IO) {
        AudiobookLibraryUseCase.listar(applicationContext).any {
            nomeNormalizadoParaDedupe(it.nome) == nomeNormalizadoParaDedupe(nome)
        }
    }

    /** Reconverter o mesmo livro sem perceber cria um audiobook duplicado; avisa antes (pode ser proposital: outra voz). */
    private suspend fun confirmarReconversao(nome: String): Boolean = suspendCancellableCoroutine { cont ->
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_audiobook_duplicado_title)
            .setMessage(getString(R.string.dialog_audiobook_duplicado_msg, nome))
            .setPositiveButton(R.string.dialog_audiobook_duplicado_confirmar) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.btn_cancelar) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
    }

    private fun confirmarCancelamento() {
        AlertDialog.Builder(this)
            .setTitle(R.string.conv_cancelar)
            .setMessage(R.string.conv_cancelar_msg)
            .setPositiveButton(R.string.conv_cancelar) { _, _ -> viewModel.cancelarProcessamento() }
            .setNegativeButton(R.string.conv_continuar, null)
            .show()
    }

    // ── Fila de processamento ────────────────────────────────────────────────

    private suspend fun carregarFila() = QueueManager.garantirCarregada(applicationContext, prefsLegado)

    private fun salvarFila() = QueueManager.persistir(lifecycleScope, applicationContext, prefsLegado)

    /** Processa o próximo item pendente da fila; ao terminar (ver [aoConcluirNaFila]) segue para o seguinte. */
    private fun rodarFila() {
        lifecycleScope.launch {
            if (jaHaConversao()) {
                Toast.makeText(this@ConversoesActivity, R.string.conv_ja_andando, Toast.LENGTH_LONG).show()
                return@launch
            }
            val proximo = QueueManager.proximoPendente()
            if (proximo == null) {
                Toast.makeText(this@ConversoesActivity, R.string.conv_fila_vazia, Toast.LENGTH_SHORT).show()
                atualizarFila()
                return@launch
            }
            if (bloqueadoPorFaltaDeRede(proximo.caminhoLocal, null, null, null)) return@launch
            val inicio = coordenador.startNext(AppPrefs(applicationContext).motorTts) ?: return@launch
            resultadoLocal = null
            salvarFila()
            tituloDoLivro = inicio.item.nome
            viewModel.pararPlayer()
            viewModel.iniciarConversao(
                arquivo = inicio.item.caminhoLocal,
                nomeAudiobook = File(inicio.item.nome).nameWithoutExtension,
                caminhoDoLivro = inicio.item.caminhoLocal,
                livroInteiro = true,
                runToken = inicio.runToken,
                filaItemId = inicio.item.id
            )
            atualizarFila()
        }
    }

    /**
     * Um item da fila terminou (sucesso, erro ou cancelamento). Quem fecha o item e inicia o próximo é o worker, mesmo sem esta
     * tela; aqui só se reconhece o próximo em andamento (e passa a acompanhá-lo) ou, se acabou, mostra o resultado.
     */
    private fun aoConcluirNaFila(evento: ConversionDoneEvent) {
        coordenador.sincronizar()
        viewModel.consumirEventoConversao()
        if (evento is ConversionDoneEvent.Sucesso) AppPrefs(this).registrarEvento(EventoAprendizado.AUDIOBOOK_GERADO.id)
        val proximo = coordenador.currentItem
        if (proximo != null) {
            tituloDoLivro = proximo.nome
            viewModel.acompanharTrabalhoAtual(proximo.runToken)
        } else {
            resultadoLocal = evento
        }
        atualizarFila()
        if (proximo == null) mostrar(viewModel.uiState.value)
    }

    private fun atualizarFila() {
        val resumo = QueueManager.resumo()
        val temFila = resumo.pendentes + resumo.processando > 0
        cardFila.visibility = if (temFila) View.VISIBLE else View.GONE
        if (!temFila) return
        findViewById<TextView>(R.id.tvConvFilaResumo).text = resources.getQuantityString(
            R.plurals.conv_fila_resumo, resumo.pendentes, resumo.pendentes
        )
        findViewById<Button>(R.id.btnConvIniciarFila).visibility =
            if (resumo.pendentes > 0 && !viewModel.uiState.value.processando) View.VISIBLE else View.GONE
    }

    // ── Estado na tela ───────────────────────────────────────────────────────

    private fun mostrar(estado: AudiobookUiState) {
        val evento = estado.conversionDone
        if (evento != null && coordenador.isProcessingQueue) {
            aoConcluirNaFila(evento)
            return
        }
        val exibir = evento ?: resultadoLocal
        when {
            estado.processando -> {
                mostrarSo(cardAgora)
                findViewById<TextView>(R.id.tvConvLivro).text = tituloDoLivro.ifBlank { getString(R.string.conv_agora_generico) }
                findViewById<TextView>(R.id.tvConvStatus).text = estado.statusMessage.ifBlank { getString(R.string.status_starting) }
                findViewById<ProgressBar>(R.id.pbConv).progress = estado.progressoPct
                findViewById<TextView>(R.id.tvConvPct).text = getString(R.string.conv_pct, estado.progressoPct)
                val naFila = coordenador.isProcessingQueue
                findViewById<TextView>(R.id.tvConvFilaPos).apply {
                    visibility = if (naFila) View.VISIBLE else View.GONE
                    if (naFila) text = getString(R.string.conv_na_fila, QueueManager.contarPendentes())
                }
            }
            exibir is ConversionDoneEvent.Sucesso -> mostrarPronto(exibir)
            exibir is ConversionDoneEvent.Erro -> {
                mostrarSo(cardErro)
                findViewById<TextView>(R.id.tvConvErroMsg).text = exibir.mensagem
            }
            exibir is ConversionDoneEvent.Cancelada -> {
                viewModel.consumirEventoConversao()
                Toast.makeText(this, R.string.status_conversion_canceled, Toast.LENGTH_SHORT).show()
            }
            else -> mostrarSo(layoutVazio)
        }
        atualizarFila()
    }

    private fun fecharResultado() {
        resultadoLocal = null
        viewModel.consumirEventoConversao()
        mostrar(viewModel.uiState.value)
    }

    private fun mostrarSo(alvo: View) {
        listOf(cardAgora, cardPronto, cardErro, layoutVazio).forEach { it.visibility = if (it === alvo) View.VISIBLE else View.GONE }
    }

    private fun mostrarPronto(evento: ConversionDoneEvent.Sucesso) {
        mostrarSo(cardPronto)
        if (!eventoRegistrado) {
            eventoRegistrado = true
            AppPrefs(this).registrarEvento(EventoAprendizado.AUDIOBOOK_GERADO.id)
        }
        findViewById<TextView>(R.id.tvConvProntoInfo).text =
            getString(R.string.conv_pronto_info, File(evento.caminho).nameWithoutExtension, evento.duracao, evento.tamanhoMb)
        val aviso = findViewById<TextView>(R.id.tvConvProntoAviso)
        val exportou = evento.exportStatus is ExportStatus.Sucesso
        aviso.visibility = if (exportou) View.GONE else View.VISIBLE
        if (!exportou) aviso.setText(R.string.conv_exportar_falhou)
        findViewById<Button>(R.id.btnConvOuvir).setOnClickListener {
            viewModel.iniciarPlayer(evento.caminho, File(evento.caminho).nameWithoutExtension)
            irParaAba(LibraryActivity::class.java)
        }
        findViewById<Button>(R.id.btnConvCompartilhar).setOnClickListener { compartilhar(evento) }
    }

    private fun compartilhar(evento: ConversionDoneEvent.Sucesso) {
        val uri = (evento.exportStatus as? ExportStatus.Sucesso)?.result?.destUri
            ?: runCatching { FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(evento.caminho)) }.getOrNull()
            ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_audiobook_chooser)))
    }

    private fun irParaAba(destino: Class<out AppCompatActivity>) {
        val intent = Intent(this, destino)
        if (destino == MeusLivrosActivity::class.java) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    companion object {
        /** Arquivo a converter (o livro ou um trecho em texto). Sem ele, [EXTRA_LIVRO_CAMINHO] é o arquivo (livro inteiro). */
        const val EXTRA_ARQUIVO = "extra_conv_arquivo"
        /** Livro da biblioteca de onde vêm voz, velocidade e tom (e o vínculo do MP3 quando é o livro inteiro). */
        const val EXTRA_LIVRO_CAMINHO = "extra_conv_livro_caminho"
        const val EXTRA_LIVRO_INTEIRO = "extra_conv_livro_inteiro"
        const val EXTRA_NOME = "extra_conv_nome"
        const val EXTRA_TITULO = "extra_conv_titulo"
        const val EXTRA_VOZ = "extra_conv_voz"
        const val EXTRA_MOTOR = "extra_conv_motor"
        const val EXTRA_RITMO = "extra_conv_ritmo"
        const val EXTRA_ESTILO = "extra_conv_estilo"
        /** Começa a processar a fila (vindo da tela da fila). */
        const val EXTRA_INICIAR_FILA = "extra_conv_iniciar_fila"
        const val EXTRA_DEBUG_ENFILEIRAR = "extra_debug_enfileirar"
        private const val SEM_RITMO = -1
        private const val ESTADO_TITULO = "estado_titulo"
        private const val TAG = "ConversoesActivity"

        /** Sobrevive à recriação da tela (rotação): o item em andamento da fila precisa continuar sendo reconhecido. */
        private val coordenador = ConversionQueueCoordinator()
    }
}
