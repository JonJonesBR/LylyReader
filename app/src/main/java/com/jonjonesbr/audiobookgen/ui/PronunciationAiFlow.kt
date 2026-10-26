package com.jonjonesbr.audiobookgen.ui

import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.EscopoPronuncia
import com.jonjonesbr.audiobookgen.domain.PronunciationAiPlanner
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import com.jonjonesbr.audiobookgen.util.LanguageDetector
import android.util.Log
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Sugerir pronúncias com IA": acha os nomes difíceis do livro, pede à IA uma grafia fonética para cada um
 * (só a lista de nomes é enviada, não o texto do livro) e mostra o resultado para o usuário marcar o que quer.
 * Com [caminhoDoLivro], as sugestões são salvas em "Este livro" por padrão (o usuário pode trocar para "Todos os livros").
 */
object PronunciationAiFlow {
    private const val ROUNDS = 2
    private const val RETRY_PAUSE_MS = 8_000L
    private var job: Job? = null

    fun start(activity: AppCompatActivity, paragraphs: List<String>, caminhoDoLivro: String? = null) {
        val client = AiSupport.client(activity)
        if (client == null) {
            AiSupport.explainMissing(activity)
            return
        }
        // Varrer o livro inteiro atrás de nomes leva segundos num livro grande: fora da thread principal
        // (antes travava a tela ao tocar no botão).
        activity.lifecycleScope.launch {
            val candidates = withContext(Dispatchers.Default) {
                val dictionary = PronunciationDialog.dicionarioEfetivo(activity, caminhoDoLivro)
                PronunciationAiPlanner.candidates(paragraphs, dictionary)
            }
            if (candidates.isEmpty()) {
                Toast.makeText(activity, R.string.pron_ai_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            val batches = PronunciationAiPlanner.batches(candidates.map { it.word })
            activity.mostrarDialogo(AlertDialog.Builder(activity)
                .setTitle(R.string.pron_ai_title)
                .setMessage(
                    activity.getString(R.string.pron_ai_confirm_message, candidates.size, batches.size) +
                        "\n\n" + activity.getString(R.string.ia_provider_line, AiSupport.label(activity))
                )
                .setPositiveButton(R.string.character_ai_send) { _, _ ->
                    run(activity, paragraphs, batches, client, caminhoDoLivro)
                }
                .setNeutralButton(R.string.ia_change) { _, _ ->
                    AiSupport.chooseProvider(activity) { start(activity, paragraphs, caminhoDoLivro) }
                }
                .setNegativeButton(android.R.string.cancel, null))
        }
    }

    /** Erro em que repetir não adianta (chave recusada, modelo inexistente): para tudo. */
    private fun isFatal(e: Exception) =
        (e is com.jonjonesbr.audiobookgen.domain.AiApiException && e.fatal) ||
            (e is com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient.ApiException && e.fatal)

    private fun errorMessage(activity: AppCompatActivity, e: Exception): String =
        if (e is com.jonjonesbr.audiobookgen.domain.AiApiException ||
            e is com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient.ApiException
        ) {
            e.message.orEmpty()
        } else {
            activity.getString(mensagemDeErro(e))
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    private fun run(
        activity: AppCompatActivity,
        paragraphs: List<String>,
        batches: List<List<String>>,
        client: com.jonjonesbr.audiobookgen.domain.AiTextClient,
        caminhoDoLivro: String?
    ) {
        val text = TextView(activity)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(text); addView(bar)
        }
        val dialog = activity.mostrarDialogo(AlertDialog.Builder(activity)
            .setTitle(R.string.pron_ai_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() })
        val language = languageCode(paragraphs)
        job?.cancel()
        job = activity.lifecycleScope.launch {
            val found = LinkedHashMap<String, String>()
            var stop: String? = null
            var lastError: String? = null
            // Cada lote é independente: um que falha (limite de uso, resposta cortada) não derruba os outros.
            // Os que falharem são tentados de novo uma vez, no fim, depois de uma pausa.
            var pending = batches
            try {
                for (round in 0 until ROUNDS) {
                    if (pending.isEmpty() || stop != null) break
                    if (round > 0) {
                        text.text = activity.getString(R.string.pron_ai_retrying, pending.size)
                        delay(RETRY_PAUSE_MS)
                    }
                    val failed = ArrayList<List<String>>()
                    for ((index, names) in pending.withIndex()) {
                        if (stop != null) {
                            failed += names
                            continue
                        }
                        if (round == 0) {
                            text.text = activity.getString(R.string.pron_ai_progress, index + 1, pending.size)
                        }
                        bar.progress = index * 100 / pending.size
                        try {
                            val answer = withContext(Dispatchers.IO) {
                                client.generate(PronunciationAiPlanner.buildPrompt(names, language))
                            }
                            val byName = PronunciationAiPlanner.parseResponse(answer)
                            if (byName.isEmpty()) {
                                failed += names
                                lastError = activity.getString(R.string.ai_resposta_vazia)
                            }
                            names.forEach { name ->
                                val spoken = PronunciationAiPlanner.spokenFor(name, byName)
                                if (spoken != null && PronunciationAiPlanner.isUsable(name, spoken)) {
                                    found[name] = spoken
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("PronunciationAiFlow", "Falha num lote da pronúncia por IA", e)
                            lastError = errorMessage(activity, e)
                            // Chave recusada ou modelo inexistente: repetir não adianta, os lotes restantes param.
                            if (isFatal(e)) stop = lastError
                            failed += names
                        }
                    }
                    pending = failed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                stop = activity.getString(R.string.character_ai_cancelled)
            }
            dialog.dismiss()
            val missing = pending.sumOf { it.size }
            val aviso = when {
                stop != null -> activity.getString(R.string.pron_ai_failed, stop)
                missing > 0 -> activity.getString(R.string.pron_ai_partial, missing, lastError.orEmpty())
                found.isEmpty() -> activity.getString(R.string.pron_ai_result_none)
                else -> null
            }
            aviso?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
            if (found.isNotEmpty()) review(activity, found, caminhoDoLivro)
        }
    }

    /** Idioma do livro ("pt", "en" ou "es"): define as regras de escrita do prompt. */
    private fun languageCode(paragraphs: List<String>): String {
        val sample = paragraphs.take(40).joinToString(" ").take(4_000)
        return when (LanguageDetector.detectLanguage(sample)) {
            "en-US" -> "en"
            "es-ES" -> "es"
            else -> "pt"
        }
    }

    /** Uma sugestão na revisão: a caixa (com o nome original) e o campo da pronúncia, que pode ser corrigido. */
    private class Sugestao(val palavra: String, val marcada: CheckBox, val falada: EditText)

    private fun review(activity: AppCompatActivity, found: Map<String, String>, caminhoDoLivro: String?) {
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val lista = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sugestoes = found.map { (palavra, falada) -> linhaDeRevisao(activity, lista, palavra, falada) }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * dp).toInt(), pad, 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.pron_ai_review_hint)
                textSize = 13f
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
            addView(ScrollView(activity).apply { addView(lista) })
        }
        var escopo = if (caminhoDoLivro != null) EscopoPronuncia.LIVRO else EscopoPronuncia.TODOS
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_ai_review_title)
            .setView(conteudo)
            .setPositiveButton(R.string.pron_ai_apply) { _, _ ->
                var dictionary = PronunciationDialog.dicionarioDoEscopo(activity, caminhoDoLivro, escopo)
                var added = 0
                sugestoes.forEach { s ->
                    val falada = s.falada.text.toString().trim()
                    if (s.marcada.isChecked && falada.isNotEmpty()) {
                        dictionary = dictionary.with(PronunciationEntry(s.palavra, falada))
                        added++
                    }
                }
                PronunciationDialog.salvarNoEscopo(activity, caminhoDoLivro, escopo, dictionary)
                Toast.makeText(activity, activity.getString(R.string.pron_ai_added, added), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
        // O rótulo entra já na criação: com texto vazio o diálogo media a barra de botões sem ele e, ao trocar
        // depois por "Salvar em: Este livro" (que empilha os botões), cortava o "Cancelar".
        if (caminhoDoLivro != null) {
            builder.setNeutralButton(
                activity.getString(R.string.pron_ai_save_to, PronunciationDialog.rotuloEscopo(activity, escopo)), null
            )
        }
        val dialogo = activity.mostrarDialogo(builder)
        // Com o teclado aberto, a lista encolhe em vez de ficar escondida atrás dele.
        dialogo.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (caminhoDoLivro != null) {
            // Botão de escopo: troca entre "Este livro" e "Todos os livros" sem fechar a revisão.
            val botao = dialogo.getButton(AlertDialog.BUTTON_NEUTRAL)
            fun rotular() {
                botao.text = activity.getString(R.string.pron_ai_save_to, PronunciationDialog.rotuloEscopo(activity, escopo))
            }
            rotular()
            botao.setOnClickListener {
                escopo = if (escopo == EscopoPronuncia.LIVRO) EscopoPronuncia.TODOS else EscopoPronuncia.LIVRO
                rotular()
            }
        }
    }

    /** Linha "☑ Nome  [pronúncia]": editar a pronúncia marca a caixa; apagar tudo equivale a desmarcar. */
    private fun linhaDeRevisao(
        activity: AppCompatActivity,
        lista: LinearLayout,
        palavra: String,
        falada: String
    ): Sugestao {
        val caixa = CheckBox(activity).apply {
            text = palavra
            isChecked = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val campo = EditText(activity).apply {
            setText(falada)
            hint = activity.getString(R.string.pron_hint_spoken)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            doAfterTextChanged { if (!it.isNullOrBlank()) caixa.isChecked = true }
        }
        lista.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caixa)
            addView(campo)
        })
        return Sugestao(palavra, caixa, campo)
    }
}
