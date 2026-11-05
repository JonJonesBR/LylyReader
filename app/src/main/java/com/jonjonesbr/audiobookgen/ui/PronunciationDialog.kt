package com.jonjonesbr.audiobookgen.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.PronunciationBookStore
import com.jonjonesbr.audiobookgen.data.PronunciationStore
import com.jonjonesbr.audiobookgen.domain.DicionarioDoLivro
import com.jonjonesbr.audiobookgen.domain.EscopoPronuncia
import com.jonjonesbr.audiobookgen.domain.EscopoPronuncias
import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.util.ScrollViewAlturaLimitada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Ouvir" do fluxo de pronúncia. O fluxo repassa o onOuvir de diálogo em diálogo (voltar, showMore, importar), então
 * passar este objeto acompanha o fluxo inteiro: [aoMostrar] é chamado para cada diálogo que abre (rb-24).
 */
internal class OuvirNoFluxo(
    private val tocar: (String) -> Unit,
    val aoMostrar: (AlertDialog) -> Unit,
) : (String) -> Unit {
    override fun invoke(texto: String) = tocar(texto)
}

/**
 * Telas do dicionário de pronúncia: editar uma palavra e gerenciar a lista.
 * No leitor (com [caminhoDoLivro]) o padrão é "Este livro" e há um seletor para "Todos os livros".
 * Em Ajustes (sem livro) tudo é global e não aparece seletor.
 */
object PronunciationDialog {

    /** Altura mínima do botão de texto, em dp: o toque tem 48dp (recomendação do Android). */
    private const val ALTURA_MINIMA_TOQUE_DP = 48

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
     * Define como [palavra] é falada. Deixar o campo "como se fala" vazio e salvar remove a entrada do escopo
     * escolhido; o botão "Remover" (só com a pronúncia já existente no escopo) faz o mesmo.
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
        // "Remover" só aparece quando o escopo escolhido já tem a pronúncia (ao trocar de escopo, o botão acompanha).
        val botaoRemover = linkDoDialogo(activity, R.string.pron_remove, R.color.color_error).apply {
            visibility = if (carregado.isEmpty()) View.GONE else View.VISIBLE
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
                    botaoRemover.visibility = if (proximo.isEmpty()) View.GONE else View.VISIBLE
                })
            }
            addView(campoPalavra)
            addView(campoFalada)
            addView(botaoRemover)
        }

        // O AlertDialog não rola o conteúdo de setView: com teclado ou tela baixa, a rolagem deixa o Remover à mão.
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_title)
            .setView(ScrollViewAlturaLimitada(activity).apply { addView(conteudo) })
            .setPositiveButton(R.string.pron_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (onOuvir != null) builder.setNeutralButton(R.string.pron_listen, null)
        val dialogo = builder.create()
        mostrarNoFluxo(activity, dialogo, onOuvir)

        /** Grava [falada] para [nova] no escopo escolhido e fecha o diálogo. [falada] vazia remove a entrada. */
        fun gravar(nova: String, falada: String) {
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

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nova = campoPalavra.text.toString().trim()
            val falada = campoFalada.text.toString().trim()
            if (nova.isEmpty()) {
                campoPalavra.error = activity.getString(R.string.pron_hint_word)
                return@setOnClickListener
            }
            gravar(nova, falada)
        }
        botaoRemover.setOnClickListener { gravar(palavra.trim(), "") }
        if (onOuvir != null) {
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val texto = campoFalada.text.toString().trim().ifEmpty { campoPalavra.text.toString().trim() }
                if (texto.isNotEmpty()) onOuvir(texto)
            }
        }
    }

    /**
     * Ação em texto no conteúdo da janela (o AlertDialog só tem três botões). É um botão de texto de verdade:
     * o TalkBack o anuncia como botão e o toque tem 48dp. O listener é ligado depois.
     */
    private fun linkDoDialogo(
        activity: AppCompatActivity,
        texto: Int,
        cor: Int,
        margemInicio: Int = 0
    ): MaterialButton {
        val dp = activity.resources.displayMetrics.density
        return MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(texto)
            setTextColor(ContextCompat.getColor(activity, cor))
            minimumHeight = (ALTURA_MINIMA_TOQUE_DP * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = margemInicio }
        }
    }

    /** Dois botões de rádio, um embaixo do outro, para "Este livro" e "Todos os livros" não quebrarem no meio. */
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
            orientation = RadioGroup.VERTICAL
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
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        // Sem padding lateral: a lista fica com o mesmo recuo dos setItems. Os demais itens recebem a margem.
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        // Com livro, "Sugerir com IA" é o botão do meio e "Importar ou exportar" vira link no topo do conteúdo.
        // Fica acima da lista de propósito: a lista ocupa o espaço que sobra e empurraria um link de baixo para fora.
        // Sem livro (ex.: Ajustes), o botão do meio é "Importar ou exportar".
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_manage_title)
            .setPositiveButton(R.string.pron_add) { _, _ ->
                show(activity, "", caminhoDoLivro, onOuvir = onOuvir) { voltar() }
            }
            .setNegativeButton(R.string.pron_close, null)
        if (onSugerirIa != null) {
            builder.setNeutralButton(R.string.pron_ai_suggest_button) { _, _ -> onSugerirIa() }
        } else {
            builder.setNeutralButton(R.string.pron_menu_more) { _, _ ->
                showMore(activity, caminhoDoLivro, onOuvir, onSugerirIa)
            }
        }
        val rotulos = entradas.map { entrada ->
            val base = "${entrada.word}  →  ${entrada.spoken}"
            if (caminhoDoLivro == null) base else "$base  ·  ${rotuloEscopo(activity, entrada.escopo)}"
        }
        // Mesmo layout de item que os setItems do AlertDialog (cor e texto do tema).
        val lista = ListView(activity).apply {
            adapter = ArrayAdapter(activity, androidx.appcompat.R.layout.select_dialog_item_material, rotulos)
        }
        val linkImportar = linkDoDialogo(activity, R.string.pron_menu_more, R.color.color_primary, margemInicio = pad)
        if (onSugerirIa != null) conteudo.addView(linkImportar)
        if (entradas.isEmpty()) {
            conteudo.addView(TextView(activity).apply {
                setText(R.string.pron_manage_empty)
                setPadding(pad, 0, pad, 0)
            })
        } else {
            conteudo.addView(lista)
        }
        val dialogo = builder.setView(conteudo).create()
        lista.setOnItemClickListener { _, _, indice, _ ->
            dialogo.dismiss()
            val entrada = entradas[indice]
            show(activity, entrada.word, caminhoDoLivro, entrada.escopo, onOuvir) { voltar() }
        }
        linkImportar.setOnClickListener {
            dialogo.dismiss()
            showMore(activity, caminhoDoLivro, onOuvir, onSugerirIa)
        }
        mostrarNoFluxo(activity, dialogo, onOuvir)
    }

    /** Importar e exportar do dicionário (com livro, do global: todos os livros). Fechar volta à lista. */
    private fun showMore(
        activity: AppCompatActivity,
        caminhoDoLivro: String?,
        onOuvir: ((String) -> Unit)?,
        onSugerirIa: (() -> Unit)?
    ) {
        // Com livro aberto, importar e exportar são do dicionário global (todos os livros): o rótulo diz isso.
        val rotuloImportar = activity.getString(if (caminhoDoLivro != null) R.string.pron_import_all else R.string.pron_import)
        val rotuloExportar = activity.getString(if (caminhoDoLivro != null) R.string.pron_export_all else R.string.pron_export)
        val itens = listOf(
            rotuloImportar to {
                importar(activity) { showManager(activity, caminhoDoLivro, onOuvir, onSugerirIa) }
            },
            rotuloExportar to { exportar(activity) }
        )
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_menu_more)
            .setItems(itens.map { it.first }.toTypedArray()) { _, indice -> itens[indice].second() }
            .setNegativeButton(R.string.pron_close) { _, _ ->
                showManager(activity, caminhoDoLivro, onOuvir, onSugerirIa)
            }
            .create()
        mostrarNoFluxo(activity, dialogo, onOuvir)
    }

    /**
     * Mostra um diálogo do fluxo de pronúncia. Se o fluxo veio com [OuvirNoFluxo] e o diálogo de fato abriu, a
     * leitura guiada fica segurada até o último diálogo fechar (rb-24). Sem o isShowing, a Activity já estaria
     * destruída: o dismiss nunca viria e a segurada ficaria presa.
     */
    private fun mostrarNoFluxo(activity: AppCompatActivity, dialogo: AlertDialog, onOuvir: ((String) -> Unit)?) {
        activity.mostrarDialogo(dialogo)
        if (dialogo.isShowing) (onOuvir as? OuvirNoFluxo)?.aoMostrar?.invoke(dialogo)
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
                        withContext(Dispatchers.IO) {
                            com.jonjonesbr.audiobookgen.domain.PronunciationTransfer.merge(PronunciationStore.get(activity), entradas)
                        }
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
