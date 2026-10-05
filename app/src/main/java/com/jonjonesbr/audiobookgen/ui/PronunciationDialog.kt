package com.jonjonesbr.audiobookgen.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.PronunciationStore
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry

/** Telas do dicionário de pronúncia: editar uma palavra e gerenciar a lista (leitor e Ajustes). */
object PronunciationDialog {

    /**
     * Define como [palavra] é falada. Deixar o campo "como se fala" vazio e salvar remove a entrada.
     * [onOuvir] (opcional) toca o texto digitado com a voz atual, sem fechar o diálogo.
     */
    fun show(
        activity: AppCompatActivity,
        palavra: String,
        onOuvir: ((String) -> Unit)? = null,
        onConcluido: () -> Unit = {}
    ) {
        val dicionario = PronunciationStore.get(activity)
        val original = dicionario.find(palavra)
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val explicacao = TextView(activity).apply {
            text = activity.getString(R.string.pron_explanation)
            textSize = 12f
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        val campoPalavra = EditText(activity).apply {
            setText(palavra.trim())
            hint = activity.getString(R.string.pron_hint_word)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val campoFalada = EditText(activity).apply {
            setText(original?.spoken.orEmpty())
            hint = activity.getString(R.string.pron_hint_spoken)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(explicacao)
            addView(campoPalavra)
            addView(campoFalada)
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_title)
            .setView(conteudo)
            .setPositiveButton(R.string.pron_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (onOuvir != null) builder.setNeutralButton(R.string.pron_listen, null)
        val dialogo = builder.create()
        dialogo.show()

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nova = campoPalavra.text.toString().trim()
            val falada = campoFalada.text.toString().trim()
            if (nova.isEmpty()) {
                campoPalavra.error = activity.getString(R.string.pron_hint_word)
                return@setOnClickListener
            }
            var atualizado = original?.let { dicionario.without(it.word) } ?: dicionario
            atualizado = if (falada.isEmpty()) atualizado.without(nova) else atualizado.with(PronunciationEntry(nova, falada))
            PronunciationStore.save(activity, atualizado)
            Toast.makeText(
                activity,
                if (falada.isEmpty()) R.string.pron_removed else R.string.pron_saved,
                Toast.LENGTH_SHORT
            ).show()
            dialogo.dismiss()
            onConcluido()
        }
        if (onOuvir != null) {
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val texto = campoFalada.text.toString().trim().ifEmpty { campoPalavra.text.toString().trim() }
                if (texto.isNotEmpty()) onOuvir(texto)
            }
        }
    }

    /** Lista das pronúncias salvas: tocar edita/remove, "Adicionar" cria uma nova. */
    fun showManager(
        activity: AppCompatActivity,
        onOuvir: ((String) -> Unit)? = null,
        /** Quando há um livro aberto: sugere pronúncias dos nomes dele com IA. */
        onSugerirIa: (() -> Unit)? = null
    ) {
        val entradas = PronunciationStore.get(activity).entries.sortedBy { it.word.lowercase() }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_manage_title)
            .setPositiveButton(R.string.pron_add) { _, _ ->
                show(activity, "", onOuvir) { showManager(activity, onOuvir, onSugerirIa) }
            }
            .setNeutralButton(R.string.pron_menu_more) { _, _ -> showMore(activity, onOuvir, onSugerirIa) }
            .setNegativeButton(R.string.pron_close, null)
        if (entradas.isEmpty()) {
            builder.setMessage(R.string.pron_manage_empty)
        } else {
            builder.setItems(entradas.map { "${it.word}  →  ${it.spoken}" }.toTypedArray()) { _, indice ->
                show(activity, entradas[indice].word, onOuvir) { showManager(activity, onOuvir, onSugerirIa) }
            }
        }
        builder.show()
    }

    private fun showMore(activity: AppCompatActivity, onOuvir: ((String) -> Unit)?, onSugerirIa: (() -> Unit)?) {
        val itens = mutableListOf(
            activity.getString(R.string.pron_import) to { importar(activity) { showManager(activity, onOuvir, onSugerirIa) } },
            activity.getString(R.string.pron_export) to { exportar(activity) }
        )
        if (onSugerirIa != null) itens += activity.getString(R.string.pron_ai_suggest) to onSugerirIa
        AlertDialog.Builder(activity)
            .setTitle(R.string.pron_menu_more)
            .setItems(itens.map { it.first }.toTypedArray()) { _, indice -> itens[indice].second() }
            .setNegativeButton(R.string.pron_close) { _, _ -> showManager(activity, onOuvir, onSugerirIa) }
            .show()
    }

    /** Importa um dicionário pronto (JSON ou linhas "palavra=falada") e junta ao atual. */
    private fun importar(activity: AppCompatActivity, depois: () -> Unit) {
        val chave = "pron_import_" + System.nanoTime()
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
        launcher = activity.activityResultRegistry.register(
            chave, androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            launcher.unregister()
            if (uri == null) { depois(); return@register }
            val texto = runCatching {
                activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            val entradas = com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.parse(texto)
            if (entradas.isEmpty()) {
                Toast.makeText(activity, R.string.pron_import_empty, Toast.LENGTH_LONG).show()
            } else {
                PronunciationStore.save(
                    activity, com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.merge(PronunciationStore.get(activity), entradas)
                )
                Toast.makeText(activity, activity.getString(R.string.pron_import_done, entradas.size), Toast.LENGTH_LONG).show()
            }
            depois()
        }
        launcher.launch(arrayOf("application/json", "text/plain", "text/csv", "text/comma-separated-values", "application/octet-stream"))
    }

    private fun exportar(activity: AppCompatActivity) {
        val chave = "pron_export_" + System.nanoTime()
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            chave, androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            launcher.unregister()
            if (uri == null) return@register
            val ok = runCatching {
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.export(PronunciationStore.get(activity)).toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            Toast.makeText(activity, if (ok) R.string.pron_export_done else R.string.pron_export_failed, Toast.LENGTH_LONG).show()
        }
        launcher.launch("lylyreader_pronuncias.json")
    }
}
