package com.jonjonesbr.audiobookgen.ui

import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.PronunciationStore
import com.jonjonesbr.audiobookgen.domain.PronunciationAiPlanner
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import com.jonjonesbr.audiobookgen.util.LanguageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Sugerir pronúncias com IA": acha os nomes difíceis do livro, pede à IA uma grafia fonética para cada um
 * (só a lista de nomes é enviada, não o texto do livro) e mostra o resultado para o usuário marcar o que quer.
 */
object PronunciationAiFlow {
    private var job: Job? = null

    fun start(activity: AppCompatActivity, paragraphs: List<String>) {
        val dictionary = PronunciationStore.get(activity)
        val candidates = PronunciationAiPlanner.candidates(paragraphs, dictionary)
        if (candidates.isEmpty()) {
            Toast.makeText(activity, R.string.pron_ai_none, Toast.LENGTH_LONG).show()
            return
        }
        val client = AiSupport.client(activity)
        if (client == null) {
            AiSupport.explainMissing(activity)
            return
        }
        val batches = PronunciationAiPlanner.batches(candidates.map { it.word })
        AlertDialog.Builder(activity)
            .setTitle(R.string.pron_ai_title)
            .setMessage(
                activity.getString(R.string.pron_ai_confirm_message, candidates.size, batches.size) +
                    "\n\n" + activity.getString(R.string.ia_provider_line, AiSupport.label(activity))
            )
            .setPositiveButton(R.string.character_ai_send) { _, _ -> run(activity, paragraphs, batches, client) }
            .setNeutralButton(R.string.ia_change) { _, _ -> AiSupport.chooseProvider(activity) { start(activity, paragraphs) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun run(
        activity: AppCompatActivity,
        paragraphs: List<String>,
        batches: List<List<String>>,
        client: com.jonjonesbr.audiobookgen.domain.AiTextClient
    ) {
        val text = TextView(activity)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(text); addView(bar)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pron_ai_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        val language = languageName(activity, paragraphs)
        job?.cancel()
        job = activity.lifecycleScope.launch {
            val found = LinkedHashMap<String, String>()
            var failure: String? = null
            try {
                batches.forEachIndexed { index, names ->
                    text.text = activity.getString(R.string.pron_ai_progress, index + 1, batches.size)
                    bar.progress = index * 100 / batches.size
                    val answer = withContext(Dispatchers.IO) { client.generate(PronunciationAiPlanner.buildPrompt(names, language)) }
                    val byName = PronunciationAiPlanner.parseResponse(answer)
                    names.forEach { name ->
                        val spoken = byName.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                        if (spoken != null && PronunciationAiPlanner.isUsable(name, spoken)) found[name] = spoken
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                failure = activity.getString(R.string.character_ai_cancelled)
            } catch (e: Exception) {
                failure = activity.getString(R.string.pron_ai_failed, e.message ?: "")
            }
            dialog.dismiss()
            if (failure != null) Toast.makeText(activity, failure, Toast.LENGTH_LONG).show()
            if (found.isNotEmpty()) review(activity, found)
            else if (failure == null) Toast.makeText(activity, R.string.pron_ai_result_none, Toast.LENGTH_LONG).show()
        }
    }

    private fun languageName(activity: AppCompatActivity, paragraphs: List<String>): String {
        val sample = paragraphs.take(40).joinToString(" ").take(4_000)
        return when (LanguageDetector.detectLanguage(sample)) {
            "en-US" -> activity.getString(R.string.pron_ai_lang_en)
            "es-ES" -> activity.getString(R.string.pron_ai_lang_es)
            else -> activity.getString(R.string.pron_ai_lang_pt)
        }
    }

    private fun review(activity: AppCompatActivity, found: Map<String, String>) {
        val entries = found.entries.toList()
        val checked = BooleanArray(entries.size) { true }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pron_ai_review_title)
            .setMultiChoiceItems(entries.map { "${it.key}  →  ${it.value}" }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.pron_ai_apply) { _, _ ->
                var dictionary = PronunciationStore.get(activity)
                var added = 0
                entries.forEachIndexed { index, entry ->
                    if (checked[index]) {
                        dictionary = dictionary.with(PronunciationEntry(entry.key, entry.value))
                        added++
                    }
                }
                PronunciationStore.save(activity, dictionary)
                Toast.makeText(activity, activity.getString(R.string.pron_ai_added, added), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
