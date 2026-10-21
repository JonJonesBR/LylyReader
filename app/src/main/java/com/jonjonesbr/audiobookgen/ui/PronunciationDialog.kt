package com.jonjonesbr.audiobookgen.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.PronunciationBookStore
import com.jonjonesbr.audiobookgen.data.PronunciationStore
import com.jonjonesbr.audiobookgen.domain.DicionarioDoLivro
import com.jonjonesbr.audiobookgen.domain.EscopoPronuncia
import com.jonjonesbr.audiobookgen.domain.EscopoPronuncias
import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telas do dicionário de pronúncia: editar uma palavra e gerenciar a lista.
 * No leitor (com [caminhoDoLivro]) o padrão é "Este livro" e há um seletor para "Todos os livros".
 * Em Ajustes (sem livro) tudo é global e não aparece seletor.
 */
object PronunciationDialog {

    /** Dicionário onde o [escopo] salva: o do livro, ou o global. Sem livro, sempre o global. */
    fun dicionarioDoEscopo(
        activity: AppCompatActivity,
        caminhoDoLivro: String?,
        escopo: EscopoPronuncia
    ): PronunciationDictionary =
        if (caminhoDoLivro != null && escopo == EscopoPronuncia.LIVRO) {
            PronunciationBookStore.doApp(activity).load(caminhoDoLivro)
        } else {
            PronunciationStore.get(activity)
        }

    /** Dicionário que vale ao ler o livro: o global mais o do livro. Sem livro, só o global. */
    fun dicionarioEfetivo(activity: AppCompatActivity, caminhoDoLivro: String?): PronunciationDictionary {
        val global = PronunciationStore.get(activity)
        return if (caminhoDoLivro == null) {
            global
        } else {
            DicionarioDoLivro.efetivo(global, PronunciationBookStore.doApp(activity).load(caminhoDoLivro))
        }
    }

    /**
     * Salva no [escopo] (em segundo plano) e, depois, manda o dicionário efetivo ao motor. [depois] roda na tela
     * principal só quando o salvamento e a sincronização acabaram, para a lista já mostrar o novo valor.
     */
    fun salvarNoEscopo(
        activity: AppCompatActivity,
        caminhoDoLivro: String?,
        escopo: EscopoPronuncia,
        dicionario: PronunciationDictionary,
        depois: () -> Unit = {}
    ) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (caminhoDoLivro != null && escopo == EscopoPronuncia.LIVRO) {
                    PronunciationBookStore.doApp(activity).save(caminhoDoLivro, dicionario)
                } else {
                    PronunciationStore.save(activity, dicionario)
                }
            }
            PythonEngineUseCase(activity).sincronizarDicionario(caminhoDoLivro)
            depois()
        }
    }

    fun rotuloEscopo(activity: AppCompatActivity, escopo: EscopoPronuncia): String =
        activity.getString(if (escopo == EscopoPronuncia.LIVRO) R.string.pron_scope_book else R.string.pron_scope_all)

    /**
     * Define como [palavra] é falada. Deixar o campo "como se fala" vazio e salvar remove a entrada do escopo escolhido.
     * [onOuvir] (opcional) toca o texto digitado com a voz atual, sem fechar o diálogo.
     */
    fun show(
        activity: AppCompatActivity,
        palavra: String,
        caminhoDoLivro: String? = null,
        escopoInicial: EscopoPronuncia = if (caminhoDoLivro != null) EscopoPronuncia.LIVRO else EscopoPronuncia.TODOS,
        onOuvir: ((String) -> Unit)? = null,
        onConcluido: () -> Unit = {}
    ) {
        var escopo = escopoInicial
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
        // Último valor carregado no campo (do escopo atual): ao trocar de escopo, só se substitui o que não foi editado.
        var carregado = dicionarioDoEscopo(activity, caminhoDoLivro, escopo).find(palavra)?.spoken.orEmpty()
        val campoFalada = EditText(activity).apply {
            setText(carregado)
            hint = activity.getString(R.string.pron_hint_spoken)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(explicacao)
            if (caminhoDoLivro != null) {
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.pron_scope_title)
                    textSize = 12f
                })
                addView(escolhaDeEscopo(activity, escopo) { novo ->
                    escopo = novo
                    val proximo = dicionarioDoEscopo(activity, caminhoDoLivro, novo).find(palavra)?.spoken.orEmpty()
                    if (campoFalada.text.toString() == carregado) campoFalada.setText(proximo)
                    carregado = proximo
                })
            }
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
        activity.mostrarDialogo(dialogo)

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nova = campoPalavra.text.toString().trim()
            val falada = campoFalada.text.toString().trim()
            if (nova.isEmpty()) {
                campoPalavra.error = activity.getString(R.string.pron_hint_word)
                return@setOnClickListener
            }
            var atualizado = dicionarioDoEscopo(activity, caminhoDoLivro, escopo).without(palavra)
            atualizado = if (falada.isEmpty()) atualizado.without(nova) else atualizado.with(PronunciationEntry(nova, falada))
            salvarNoEscopo(activity, caminhoDoLivro, escopo, atualizado) {
                Toast.makeText(
                    activity,
                    if (falada.isEmpty()) R.string.pron_removed else R.string.pron_saved,
                    Toast.LENGTH_SHORT
                ).show()
                dialogo.dismiss()
                onConcluido()
            }
        }
        if (onOuvir != null) {
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val texto = campoFalada.text.toString().trim().ifEmpty { campoPalavra.text.toString().trim() }
                if (texto.isNotEmpty()) onOuvir(texto)
            }
        }
    }

    /** Dois botões de rádio, lado a lado: "Este livro" e "Todos os livros". */
    private fun escolhaDeEscopo(
        activity: AppCompatActivity,
        inicial: EscopoPronuncia,
        aoMudar: (EscopoPronuncia) -> Unit
    ): RadioGroup {
        val rbLivro = RadioButton(activity).apply {
            id = View.generateViewId()
            text = rotuloEscopo(activity, EscopoPronuncia.LIVRO)
        }
        val rbTodos = RadioButton(activity).apply {
            id = View.generateViewId()
            text = rotuloEscopo(activity, EscopoPronuncia.TODOS)
        }
        return RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbLivro)
            addView(rbTodos)
            check(if (inicial == EscopoPronuncia.LIVRO) rbLivro.id else rbTodos.id)
            setOnCheckedChangeListener { _, id ->
                aoMudar(if (id == rbLivro.id) EscopoPronuncia.LIVRO else EscopoPronuncia.TODOS)
            }
        }
    }

    /**
     * Lista das pronúncias salvas: tocar edita/remove, "Adicionar" cria uma nova. Com livro, cada entrada mostra
     * de onde vem (este livro ou todos os livros).
     */
    fun showManager(
        activity: AppCompatActivity,
        caminhoDoLivro: String? = null,
        onOuvir: ((String) -> Unit)? = null,
        /** Quando há um livro aberto: sugere pronúncias dos nomes dele com IA. */
        onSugerirIa: (() -> Unit)? = null
    ) {
        val doLivro = caminhoDoLivro?.let { PronunciationBookStore.doApp(activity).load(it) } ?: PronunciationDictionary.EMPTY
        val entradas = EscopoPronuncias.listar(PronunciationStore.get(activity), doLivro)
        val voltar = { showManager(activity, caminhoDoLivro, onOuvir, onSugerirIa) }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_manage_title)
            .setPositiveButton(R.string.pron_add) { _, _ ->
                show(activity, "", caminhoDoLivro, onOuvir = onOuvir) { voltar() }
            }
            .setNeutralButton(R.string.pron_menu_more) { _, _ -> showMore(activity, caminhoDoLivro, onOuvir, onSugerirIa) }
            .setNegativeButton(R.string.pron_close, null)
        if (entradas.isEmpty()) {
            builder.setMessage(R.string.pron_manage_empty)
        } else {
            val rotulos = entradas.map { entrada ->
                val base = "${entrada.word}  →  ${entrada.spoken}"
                if (caminhoDoLivro == null) base else "$base  ·  ${rotuloEscopo(activity, entrada.escopo)}"
            }
            builder.setItems(rotulos.toTypedArray()) { _, indice ->
                val entrada = entradas[indice]
                show(activity, entrada.word, caminhoDoLivro, entrada.escopo, onOuvir) { voltar() }
            }
        }
        activity.mostrarDialogo(builder)
    }

    private fun showMore(
        activity: AppCompatActivity,
        caminhoDoLivro: String?,
        onOuvir: ((String) -> Unit)?,
        onSugerirIa: (() -> Unit)?
    ) {
        // Com livro aberto, importar e exportar são do dicionário global (todos os livros): o rótulo diz isso.
        val rotuloImportar = activity.getString(if (caminhoDoLivro != null) R.string.pron_import_all else R.string.pron_import)
        val rotuloExportar = activity.getString(if (caminhoDoLivro != null) R.string.pron_export_all else R.string.pron_export)
        val itens = mutableListOf(
            rotuloImportar to {
                importar(activity) { showManager(activity, caminhoDoLivro, onOuvir, onSugerirIa) }
            },
            rotuloExportar to { exportar(activity) }
        )
        if (onSugerirIa != null) itens += activity.getString(R.string.pron_ai_suggest) to onSugerirIa
        activity.mostrarDialogo(AlertDialog.Builder(activity)
            .setTitle(R.string.pron_menu_more)
            .setItems(itens.map { it.first }.toTypedArray()) { _, indice -> itens[indice].second() }
            .setNegativeButton(R.string.pron_close) { _, _ -> showManager(activity, caminhoDoLivro, onOuvir, onSugerirIa) })
    }

    /** Importa um dicionário pronto (JSON ou linhas "palavra=falada") e junta ao global. */
    private fun importar(activity: AppCompatActivity, depois: () -> Unit) {
        val chave = "pron_import_" + System.nanoTime()
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
        launcher = activity.activityResultRegistry.register(
            chave, androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            launcher.unregister()
            if (uri == null) { depois(); return@register }
            activity.lifecycleScope.launch {
                val entradas = withContext(Dispatchers.IO) {
                    val texto = runCatching {
                        activity.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull().orEmpty()
                    com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.parse(texto)
                }
                if (entradas.isEmpty()) {
                    Toast.makeText(activity, R.string.pron_import_empty, Toast.LENGTH_LONG).show()
                } else {
                    salvarNoEscopo(
                        activity, null, EscopoPronuncia.TODOS,
                        com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.merge(PronunciationStore.get(activity), entradas)
                    ) {
                        Toast.makeText(activity, activity.getString(R.string.pron_import_done, entradas.size), Toast.LENGTH_LONG).show()
                        depois()
                    }
                    return@launch
                }
                depois()
            }
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
