package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("TooGenericExceptionCaught", "SwallowedException")
class ErrorLogsActivity : AppCompatActivity() {

    private lateinit var rvErrorLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var btnVoltarLogs: ImageButton
    private lateinit var btnLimparTodosLogs: ImageButton
    
    private var logFiles = mutableListOf<File>()
    private lateinit var adapter: LogsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_logs)

        rvErrorLogs = findViewById(R.id.rvErrorLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        btnVoltarLogs = findViewById(R.id.btnVoltarLogs)
        btnLimparTodosLogs = findViewById(R.id.btnLimparTodosLogs)

        btnVoltarLogs.setOnClickListener { finish() }
        btnLimparTodosLogs.setOnClickListener { mostrarConfirmacaoLimparTudo() }

        rvErrorLogs.layoutManager = LinearLayoutManager(this)
        adapter = LogsAdapter()
        rvErrorLogs.adapter = adapter

        carregarLogs()
    }

    private fun carregarLogs() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                CrashLogWriter.getLogFiles(this@ErrorLogsActivity)
            }
            logFiles.clear()
            logFiles.addAll(files)
            adapter.notifyDataSetChanged()
            atualizarTela()
        }
    }

    private fun atualizarTela() {
        if (logFiles.isEmpty()) {
            tvEmptyLogs.visibility = View.VISIBLE
            rvErrorLogs.visibility = View.GONE
            btnLimparTodosLogs.visibility = View.GONE
        } else {
            tvEmptyLogs.visibility = View.GONE
            rvErrorLogs.visibility = View.VISIBLE
            btnLimparTodosLogs.visibility = View.VISIBLE
        }
    }

    private fun mostrarConfirmacaoLimparTudo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_limpar_fila)
            .setMessage("Deseja apagar todos os relatórios de erro?")
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        CrashLogWriter.clearAllLogs(this@ErrorLogsActivity)
                    }
                    Toast.makeText(this@ErrorLogsActivity, R.string.toast_logs_limpos, Toast.LENGTH_SHORT).show()
                    carregarLogs()
                }
            }
            .setNegativeButton(R.string.btn_cancelar, null)
            .show()
    }

    private fun formatLogName(name: String): String {
        val cleaned = name.removePrefix("lyly_error_").removeSuffix(".txt")
        val parts = cleaned.split("T")
        if (parts.size == 2) {
            val date = parts[0]
            val timePart = parts[1].substringBefore("-SSS").substringBefore("Z")
            val timeFormatted = timePart.replace('-', ':')
            return "$date $timeFormatted"
        }
        return cleaned
    }

    private fun shareLogFile(file: File) {
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.logs_compartilhar_titulo)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_erro_compartilhar_log, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showLogDetails(file: File) {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            getString(R.string.logs_erro_ler, e.message ?: "")
        }

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

        AlertDialog.Builder(this)
            .setTitle(formatLogName(file.name))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.logs_btn_copiar)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Lyly Error Log", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copiado_area_transferencia, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.logs_btn_compartilhar)) { _, _ ->
                shareLogFile(file)
            }
            .setNegativeButton(getString(R.string.logs_btn_fechar), null)
            .show()
    }

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
                        carregarLogs()
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
            .show()
    }

    private inner class LogsAdapter : RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_error_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = logFiles[position]
            holder.tvLogDate.text = formatLogName(file.name)
            
            val kbSize = file.length().toDouble() / KB_FACTOR
            holder.tvLogSize.text = "%.2f KB".format(kbSize)

            holder.itemView.setOnClickListener {
                showLogDetails(file)
            }

            holder.btnShareLog.setOnClickListener {
                shareLogFile(file)
            }

            holder.btnDeleteLog.setOnClickListener {
                deleteLogFile(file)
            }
        }

        override fun getItemCount(): Int = logFiles.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLogDate: TextView = view.findViewById(R.id.tvLogDate)
            val tvLogSize: TextView = view.findViewById(R.id.tvLogSize)
            val btnShareLog: ImageButton = view.findViewById(R.id.btnShareLog)
            val btnDeleteLog: ImageButton = view.findViewById(R.id.btnDeleteLog)
        }
    }

    companion object {
        private const val DETAIL_PADDING_DP = 24
        private const val LOG_TEXT_SIZE = 12f
        private const val KB_FACTOR = 1024.0
    }
}
