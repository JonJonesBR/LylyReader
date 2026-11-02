package com.jonjonesbr.audiobookgen.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.DiaRelativo
import com.jonjonesbr.audiobookgen.domain.ResumoRegistroErro
import com.jonjonesbr.audiobookgen.domain.TipoRegistroErro
import com.jonjonesbr.audiobookgen.domain.diaRelativo
import com.jonjonesbr.audiobookgen.domain.instanteDoNomeDoLog
import com.jonjonesbr.audiobookgen.domain.resumirArquivoDeErro
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.marcarComoLink
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("TooGenericExceptionCaught", "SwallowedException", "TooManyFunctions")
class ErrorLogsActivity : AppCompatActivity() {

    /** Um registro da lista: arquivo, resumo do motivo e o instante gravado no nome. */
    private data class ItemLog(val arquivo: File, val resumo: ResumoRegistroErro, val instante: Long)

    private val dialogos = DialogosAbertos(this)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)

    private lateinit var rvErrorLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var btnVoltarLogs: ImageButton
    private lateinit var btnLimparTodosLogs: ImageButton
    private lateinit var tvLinkGithubLogs: TextView
    private lateinit var btnEnviarTodosLogs: Button

    private var itens: List<ItemLog> = emptyList()
    private lateinit var adapter: LogsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_logs)

        rvErrorLogs = findViewById(R.id.rvErrorLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        btnVoltarLogs = findViewById(R.id.btnVoltarLogs)
        btnLimparTodosLogs = findViewById(R.id.btnLimparTodosLogs)
        tvLinkGithubLogs = findViewById(R.id.tvLinkGithubLogs)
        btnEnviarTodosLogs = findViewById(R.id.btnEnviarTodosLogs)

        btnVoltarLogs.setOnClickListener { finish() }
        btnLimparTodosLogs.setOnClickListener { mostrarConfirmacaoLimparTudo() }
        tvLinkGithubLogs.marcarComoLink()
        tvLinkGithubLogs.setOnClickListener { abrirGithub() }
        btnEnviarTodosLogs.setOnClickListener { enviarTodos() }

        rvErrorLogs.layoutManager = LinearLayoutManager(this)
        adapter = LogsAdapter()
        rvErrorLogs.adapter = adapter

        carregarLogs()
    }

    private fun carregarLogs() {
        lifecycleScope.launch {
            // Só as primeiras linhas de cada arquivo são lidas, e fora da thread principal.
            itens = withContext(Dispatchers.IO) {
                CrashLogWriter.getLogFiles(this@ErrorLogsActivity).map { arquivo ->
                    ItemLog(
                        arquivo = arquivo,
                        resumo = resumirArquivoDeErro(arquivo),
                        instante = instanteDoNomeDoLog(arquivo.name) ?: arquivo.lastModified()
                    )
                }
            }
            adapter.submitList(itens)
            atualizarTela()
        }
    }

    private fun atualizarTela() {
        if (itens.isEmpty()) {
            tvEmptyLogs.visibility = View.VISIBLE
            rvErrorLogs.visibility = View.GONE
            btnLimparTodosLogs.visibility = View.GONE
            btnEnviarTodosLogs.visibility = View.GONE
        } else {
            tvEmptyLogs.visibility = View.GONE
            rvErrorLogs.visibility = View.VISIBLE
            btnLimparTodosLogs.visibility = View.VISIBLE
            btnEnviarTodosLogs.visibility = View.VISIBLE
        }
    }

    private fun mostrarConfirmacaoLimparTudo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_limpar_fila)
            .setMessage(R.string.logs_confirmar_apagar_todos)
            .setPositiveButton(R.string.logs_btn_apagar_todos) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        CrashLogWriter.clearAllLogs(this@ErrorLogsActivity)
                    }
                    Toast.makeText(this@ErrorLogsActivity, R.string.toast_logs_limpos, Toast.LENGTH_SHORT).show()
                    carregarLogs()
                }
            }
            .setNegativeButton(R.string.btn_cancelar, null)
            .exibir()
    }

    /** "Hoje, 19:02", "Ontem, 14:50" ou "01/11, 14:30", no formato de hora e de data do aparelho. */
    private fun formatarQuando(instante: Long): String {
        val data = Date(instante)
        val hora = android.text.format.DateFormat.getTimeFormat(this).format(data)
        return when (diaRelativo(instante, System.currentTimeMillis())) {
            DiaRelativo.HOJE -> getString(R.string.logs_quando_hoje, hora)
            DiaRelativo.ONTEM -> getString(R.string.logs_quando_ontem, hora)
            DiaRelativo.OUTRO -> {
                val locale = Locale.getDefault()
                val padrao = android.text.format.DateFormat.getBestDateTimePattern(locale, "dd/MM")
                getString(R.string.logs_quando_data, SimpleDateFormat(padrao, locale).format(data), hora)
            }
        }
    }

    private fun textoDoResumo(resumo: ResumoRegistroErro): String = when (resumo.tipo) {
        TipoRegistroErro.ENCERRAMENTO_NORMAL -> getString(
            if (resumo.poucaMemoria) R.string.logs_resumo_pouca_memoria else R.string.logs_resumo_encerrado
        )
        TipoRegistroErro.TRAVAMENTO -> resumo.excecao
            ?.let { getString(R.string.logs_resumo_travamento_excecao, it) }
            ?: getString(R.string.logs_resumo_travamento)
        TipoRegistroErro.SEM_RESPOSTA -> getString(R.string.logs_resumo_sem_resposta)
        TipoRegistroErro.OUTRO -> getString(R.string.logs_resumo_outro)
    }

    /** Na primeira vez, avisa o que o log pode conter antes de mandá-lo para outro app. */
    private fun comAvisoDePrivacidade(acao: () -> Unit) {
        val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs(this)
        if (prefs.avisoCompartilharLogVisto) {
            acao()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_aviso_privacidade_titulo)
            .setMessage(R.string.logs_aviso_privacidade_msg)
            .setPositiveButton(R.string.logs_btn_compartilhar) { _, _ ->
                prefs.avisoCompartilharLogVisto = true
                acao()
            }
            .setNegativeButton(R.string.btn_cancelar, null)
            .exibir()
    }

    private fun shareLogFile(file: File) {
        comAvisoDePrivacidade { compartilharLogs(listOf(file)) }
    }

    private fun enviarTodos() {
        val arquivos = itens.map { it.arquivo }
        comAvisoDePrivacidade { compartilharLogs(arquivos) }
    }

    private fun compartilharLogs(arquivos: List<File>) {
        if (arquivos.isEmpty()) return
        lifecycleScope.launch {
            try {
                // Logs gravados antes do filtro de chaves (CrashLogWriter) também saem sem elas.
                // Lê e reescreve os arquivos fora da thread principal (Enviar todos junta até 20).
                withContext(Dispatchers.IO) { arquivos.forEach { ocultarSegredosDoArquivo(it) } }
                val authority = "$packageName.fileprovider"
                val uris = arquivos.map { FileProvider.getUriForFile(this@ErrorLogsActivity, authority, it) }
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "text/plain"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                }
                // O ClipData leva a permissão de leitura a todos os arquivos, não só ao primeiro.
                intent.clipData = ClipData.newUri(contentResolver, "logs", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val titulo = if (uris.size == 1) {
                    R.string.logs_compartilhar_titulo
                } else {
                    R.string.logs_compartilhar_todos_titulo
                }
                startActivity(Intent.createChooser(intent, getString(titulo)))
            } catch (e: Exception) {
                Log.w("ErrorLogsActivity", "Falha ao compartilhar log", e)
                Toast.makeText(
                    this@ErrorLogsActivity,
                    getString(R.string.toast_erro_compartilhar_log, getString(mensagemDeErro(e))),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun ocultarSegredosDoArquivo(file: File) {
        runCatching {
            val texto = file.readText(Charsets.UTF_8)
            val limpo = com.jonjonesbr.audiobookgen.util.ocultarSegredos(texto)
            if (limpo != texto) file.writeText(limpo, Charsets.UTF_8)
        }
    }

    private fun abrirGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL_ISSUES)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.logs_erro_abrir_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogDetails(item: ItemLog) {
        lifecycleScope.launch {
            val lido = withContext(Dispatchers.IO) { runCatching { item.arquivo.readText(Charsets.UTF_8) } }
            val content = lido.getOrElse { e ->
                Log.w("ErrorLogsActivity", "Falha ao ler log", e)
                getString(R.string.logs_erro_ler, getString(mensagemDeErro(e)))
            }
            mostrarDetalhesLog(item, content)
        }
    }

    private fun mostrarDetalhesLog(item: ItemLog, content: String) {
        val density = resources.displayMetrics.density
        val paddingPx = (DETAIL_PADDING_DP * density).toInt()

        val textView = TextView(this).apply {
            text = content
            textSize = LOG_TEXT_SIZE
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            setTextIsSelectable(true)
        }

        val scrollView = NestedScrollView(this).apply {
            addView(textView)
        }

        val titulo = getString(
            R.string.logs_detalhes_titulo,
            formatarQuando(item.instante),
            formatarTamanho(item.arquivo)
        )

        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.logs_btn_copiar)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Lyly Error Log", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copiado_area_transferencia, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.logs_btn_compartilhar)) { _, _ ->
                shareLogFile(item.arquivo)
            }
            .setNegativeButton(getString(R.string.logs_btn_fechar), null)
            .exibir()
    }

    private fun formatarTamanho(file: File): String = "%.2f KB".format(file.length().toDouble() / KB_FACTOR)

    private fun deleteLogFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_deletar)
            .setMessage(getString(R.string.logs_confirmar_exclusao))
            .setPositiveButton(getString(R.string.logs_btn_excluir)) { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (deleted) {
                        Toast.makeText(this@ErrorLogsActivity, R.string.toast_log_apagado, Toast.LENGTH_SHORT).show()
                        removerLogDaLista(file)
                    } else {
                        Toast.makeText(
                            this@ErrorLogsActivity,
                            R.string.toast_erro_apagar_log,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancelar, null)
            .exibir()
    }

    private fun removerLogDaLista(file: File) {
        if (itens.none { it.arquivo == file }) {
            carregarLogs()
            return
        }
        itens = itens.filterNot { it.arquivo == file }
        adapter.submitList(itens)
        atualizarTela()
    }

    private inner class LogsAdapter : ListAdapter<ItemLog, LogsAdapter.ViewHolder>(DIFF_LOGS) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_error_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.tvLogDate.text = formatarQuando(item.instante)
            holder.tvLogResumo.text = textoDoResumo(item.resumo)
            // Encerramento normal do sistema fica em cor discreta: não é defeito do app.
            val cor = if (item.resumo.tipo == TipoRegistroErro.ENCERRAMENTO_NORMAL) {
                R.color.color_on_surface_muted
            } else {
                R.color.color_on_surface
            }
            holder.tvLogResumo.setTextColor(ContextCompat.getColor(holder.itemView.context, cor))

            holder.itemView.setOnClickListener {
                showLogDetails(item)
            }

            holder.btnShareLog.setOnClickListener {
                shareLogFile(item.arquivo)
            }

            holder.btnDeleteLog.setOnClickListener {
                deleteLogFile(item.arquivo)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLogDate: TextView = view.findViewById(R.id.tvLogDate)
            val tvLogResumo: TextView = view.findViewById(R.id.tvLogResumo)
            val btnShareLog: ImageButton = view.findViewById(R.id.btnShareLog)
            val btnDeleteLog: ImageButton = view.findViewById(R.id.btnDeleteLog)
        }
    }

    companion object {
        private const val DETAIL_PADDING_DP = 24
        private const val LOG_TEXT_SIZE = 12f
        private const val KB_FACTOR = 1024.0
        private const val URL_ISSUES = "https://github.com/JonJonesBR/LylyReader/issues"

        private val DIFF_LOGS = object : DiffUtil.ItemCallback<ItemLog>() {
            override fun areItemsTheSame(antigo: ItemLog, novo: ItemLog): Boolean = antigo.arquivo == novo.arquivo
            override fun areContentsTheSame(antigo: ItemLog, novo: ItemLog): Boolean = antigo == novo
        }
    }
}
