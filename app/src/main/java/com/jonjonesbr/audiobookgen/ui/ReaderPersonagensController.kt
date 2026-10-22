package com.jonjonesbr.audiobookgen.ui

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.DetectedSpeaker
import com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.SpeakerAiPlanner
import com.jonjonesbr.audiobookgen.domain.SpeakerAiRunner
import com.jonjonesbr.audiobookgen.domain.SpeakerAiStore
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionResult
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import com.jonjonesbr.audiobookgen.domain.SpeakerEdits
import com.jonjonesbr.audiobookgen.domain.SpeakerEditsStore
import com.jonjonesbr.audiobookgen.domain.SpeakerGender
import com.jonjonesbr.audiobookgen.domain.SpeakerGenderInferrer
import com.jonjonesbr.audiobookgen.domain.SpeakerVoiceSuggester
import com.jonjonesbr.audiobookgen.domain.SpeechLines
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache
import com.jonjonesbr.audiobookgen.tts.AndroidVoiceId
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Personagens (falantes) do leitor: análise local por livro, vozes por personagem, sugestão de
 * vozes, análise por IA, pronúncia dos nomes e correção manual (conferir falas, corrigir uma fala,
 * fundir nomes). Extraído de [ReaderActivity] pra reduzir o tamanho do arquivo (refatoração pura).
 * Padrão do projeto: activity + viewModel + callbacks no construtor, sem acessar campos da Activity.
 */
class ReaderPersonagensController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterGuidedPlayerManager: () -> GuidedPlayerManager?,
        /** Toca a pronúncia digitada com a voz e o motor do livro (função da Activity). */
        val ouvirPronuncia: (String) -> Unit,
    )

    companion object {
        private const val HINT_PERSONAGENS_MS = 10_000
        private const val EXCERTO_FALA_MAX = 90
    }

    private val caminhoArquivo: String get() = callbacks.obterCaminhoArquivo()
    private val guidedPlayerManager: GuidedPlayerManager? get() = callbacks.obterGuidedPlayerManager()
    // Lido só dentro dos métodos (não no construtor): ver armadilha de recursos no construtor.
    private val appPrefs by lazy { AppPrefs(activity) }

    private val dialogos = DialogosAbertos(activity)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)
    // Sem argumentos, chama getString(id) puro (getString com varargs vazio passa por String.format).
    private fun getString(id: Int, vararg args: Any): String =
        if (args.isEmpty()) activity.getString(id) else activity.getString(id, *args)

    private val speakerAttributionStore by lazy {
        SpeakerAttributionStore(File(activity.filesDir, "speaker_attribution"))
    }
    private val speakerAiStore by lazy { SpeakerAiStore(File(activity.filesDir, "speaker_attribution")) }
    private val speakerEditsStore by lazy { SpeakerEditsStore(File(activity.filesDir, "speaker_attribution")) }
    private var speakerAiJob: Job? = null
    private var speakerAnalysisJob: Job? = null
    private var speakerAnalysisParagraphs: List<String>? = null
    private var speakerAttribution: SpeakerAttributionResult? = null
    /** Gênero PROVÁVEL por personagem (heurística sobre o nome); só para resumo e sugestão de vozes. */
    private var speakerGenders: Map<String, SpeakerGender> = emptyMap()
    private val speakerVoiceDownloadFlow by lazy { VoiceDownloadFlow(activity) }

    /** Analisa localmente sem bloquear a abertura nem a primeira fala; o resultado fica por livro. */
    fun prepararAtribuicaoDeFalantes(paragrafos: List<Paragrafo>) {
        val textos = paragrafos.map { it.texto }
        if (textos == speakerAnalysisParagraphs) return
        speakerAnalysisParagraphs = textos
        speakerAnalysisJob?.cancel()
        val identidadeLivro = caminhoArquivo
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragraph -> paragraph.texto } == textos }
            ?.capitulos
            .orEmpty()
        speakerAnalysisJob = activity.lifecycleScope.launch {
            val (resultado, generos) = withContext(Dispatchers.IO) {
                val analisado = speakerAttributionStore.load(identidadeLivro, textos)
                    ?: run {
                        // Reanálise (livro novo ou algoritmo atualizado): mantém as vozes já escolhidas.
                        val vozes = speakerAttributionStore.previousVoices(identidadeLivro, textos)
                        val novo = OfflineSpeakerAttributor.analyze(
                            textos,
                            aiOverrides = speakerAiStore.load(identidadeLivro, textos).labels,
                            edits = speakerEditsStore.load(identidadeLivro, textos)
                        ).let { resultado ->
                            if (vozes.isEmpty()) resultado else resultado.copy(
                                speakers = resultado.speakers.map { it.copy(voiceId = vozes[it.id] ?: it.voiceId) }
                            )
                        }
                        speakerAttributionStore.save(identidadeLivro, textos, novo, capitulos)
                        novo
                    }
                analisado to analisado.speakers.associate {
                    it.id to SpeakerGenderInferrer.infer(it.name, textos)
                }
            }
            val textoAtual = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }
            if (caminhoArquivo == identidadeLivro && textoAtual == textos) {
                speakerAttribution = resultado
                speakerGenders = generos
                guidedPlayerManager?.setSpeakerAttribution(resultado)
                avisarPersonagensEncontradosSeNecessario(resultado)
            }
        }
    }

    /** Caminho do mapa de vozes dos personagens para o download (null se nenhum personagem tem voz). */
    fun caminhoMapaDePersonagens(textosDoLivro: List<String>): String? =
        speakerAttribution
            ?.takeIf { result -> result.speakers.any { !it.voiceId.isNullOrBlank() } }
            ?.takeIf { speakerAnalysisParagraphs == textosDoLivro }
            ?.let { speakerAttributionStore.fileForBook(caminhoArquivo) }
            ?.takeIf { it.isFile }
            ?.absolutePath

    /** Espera a análise de falantes em andamento, se houver. */
    suspend fun aguardarAnaliseDeFalantes() {
        speakerAnalysisJob?.join()
    }

    fun abrirConfiguracaoVozesPersonagens() {
        val current = speakerAttribution
        if (current != null) {
            mostrarListaVozesPersonagens(current)
            return
        }
        activity.lifecycleScope.launch {
            speakerAnalysisJob?.join()
            val result = speakerAttribution
            if (result == null || result.speakers.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.character_voices_title)
                    .setMessage(R.string.character_voices_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .exibir()
            } else {
                mostrarListaVozesPersonagens(result)
            }
        }
    }

    private fun mostrarListaVozesPersonagens(result: SpeakerAttributionResult) {
        if (result.speakers.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.character_voices_title)
                .setMessage(R.string.character_voices_empty)
                .setPositiveButton(android.R.string.ok, null)
                .exibir()
            return
        }
        val labels = result.speakers.map { speaker ->
            getString(
                R.string.character_voice_assignment_item,
                speaker.name,
                speaker.utteranceCount,
                nomeVozPersonagem(speaker),
                rotuloGenero(speaker)
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_voices_title)
            .setItems((labels + getString(R.string.character_ai_action)).toTypedArray()) { _, position ->
                val personagem = result.speakers.getOrNull(position)
                if (personagem != null) mostrarOpcoesVozPersonagem(result, personagem) else confirmarAnaliseComIa()
            }
            .setPositiveButton(R.string.character_voices_suggest) { _, _ -> sugerirVozesPersonagens(result) }
            .setNeutralButton(R.string.character_voices_pronunciation) { _, _ -> mostrarPronunciaDosNomes(result) }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    // ── Análise de personagens por IA (Gemini), só por ação explícita ────────────────────────────

    private fun confirmarAnaliseComIa() {
        val cliente = AiSupport.client(activity)
        if (cliente == null) {
            AiSupport.explainMissing(activity)
            return
        }
        val textos = speakerAnalysisParagraphs ?: return
        val estado = speakerAiStore.load(caminhoArquivo, textos)
        val lotes = SpeakerAiPlanner.planBatches(textos, estado.doneParagraphs)
        if (lotes.isEmpty()) {
            Toast.makeText(activity, R.string.character_ai_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val paragrafos = lotes.sumOf { it.classify.size }
        val caracteres = lotes.sumOf { lote -> (lote.classify + lote.context).sumOf { textos[it].length } }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_ai_confirm_title)
            .setMessage(
                getString(
                    R.string.character_ai_confirm_message, paragrafos,
                    getString(R.string.character_ai_size_kb, (caracteres / 1024).coerceAtLeast(1)), lotes.size
                ) + "\n\n" + getString(R.string.character_ai_model_line, rotuloModeloIa())
            )
            .setPositiveButton(R.string.character_ai_send) { _, _ -> executarAnaliseComIa(cliente, textos, estado) }
            .setNeutralButton(R.string.ia_change) { _, _ -> opcoesDaIa() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun rotuloModeloIa(): String =
        if (AiSupport.provider(activity) == com.jonjonesbr.audiobookgen.domain.AiProvider.GEMINI)
            appPrefs.geminiModeloAnalise.ifBlank { getString(R.string.character_ai_model_auto) }
        else AiSupport.label(activity)

    /** Trocar de IA e, no Gemini, também o modelo. */
    private fun opcoesDaIa() {
        val itens = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.ia_provider_title) to { AiSupport.chooseProvider(activity) { confirmarAnaliseComIa() } }
        )
        if (AiSupport.provider(activity) == com.jonjonesbr.audiobookgen.domain.AiProvider.GEMINI) {
            GeminiSpeakerClient.parseKeys(SecurePreferences.getGeminiKeys(activity)).firstOrNull()?.let { chave ->
                itens += getString(R.string.character_ai_model_change) to { escolherModeloIa(chave) }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.ia_change)
            .setItems(itens.map { it.first }.toTypedArray()) { _, indice -> itens[indice].second() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .exibir()
    }

    /** Escolha do modelo: automático, um da lista que a API libera para a chave, ou um nome digitado. */
    private fun escolherModeloIa(chave: String) {
        val carregando = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_ai_model_title)
            .setMessage(R.string.character_ai_model_loading)
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .exibir()
        activity.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { runCatching { GeminiSpeakerClient.listModels(chave) } }
            if (!carregando.isShowing) return@launch
            carregando.dismiss()
            val modelos = resultado.getOrDefault(emptyList())
            resultado.exceptionOrNull()?.let {
                Log.w("ReaderActivity", "Falha ao listar modelos da IA", it)
                val motivo = if (it is com.jonjonesbr.audiobookgen.domain.AiApiException) it.message.orEmpty()
                    else getString(mensagemDeErro(it))
                activity.mostrarErro(getString(R.string.character_ai_model_failed, motivo))
            }
            val opcoes = listOf(getString(R.string.character_ai_model_auto)) + modelos +
                getString(R.string.character_ai_model_custom)
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.character_ai_model_title)
                .setItems(opcoes.toTypedArray()) { _, posicao ->
                    when (posicao) {
                        0 -> { appPrefs.geminiModeloAnalise = ""; confirmarAnaliseComIa() }
                        opcoes.lastIndex -> digitarModeloIa()
                        else -> { appPrefs.geminiModeloAnalise = opcoes[posicao]; confirmarAnaliseComIa() }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
                .exibir()
        }
    }

    private fun digitarModeloIa() {
        val campo = android.widget.EditText(activity).apply {
            hint = getString(R.string.character_ai_model_custom_hint)
            setText(appPrefs.geminiModeloAnalise)
            setSingleLine()
        }
        val nota = android.widget.TextView(activity).apply {
            text = getString(R.string.character_ai_model_note)
            textSize = 12f
        }
        val conteudo = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(campo)
            addView(nota)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_ai_model_custom)
            .setView(conteudo)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                appPrefs.geminiModeloAnalise = campo.text.toString().trim().removePrefix("models/")
                confirmarAnaliseComIa()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> confirmarAnaliseComIa() }
            .exibir()
    }

    private fun executarAnaliseComIa(cliente: com.jonjonesbr.audiobookgen.domain.AiTextClient, textos: List<String>, inicial: SpeakerAiStore.State) {
        val identidade = caminhoArquivo
        val barra = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val texto = android.widget.TextView(activity)
        val conteudo = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(texto)
            addView(barra)
        }
        val dialogo = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_ai_progress_title)
            .setView(conteudo)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> speakerAiJob?.cancel() }
            .exibir()
        var ultimo = inicial
        speakerAiJob?.cancel()
        speakerAiJob = activity.lifecycleScope.launch {
            var mensagem: String? = null
            try {
                val elenco = speakerAttribution?.speakers?.map { it.name }.orEmpty()
                SpeakerAiRunner(cliente).run(
                    paragraphs = textos,
                    initial = inicial,
                    localCast = elenco,
                    onProgress = { p ->
                        texto.text = getString(R.string.character_ai_progress, p.batchesDone + 1, p.batchesTotal)
                        barra.progress = if (p.batchesTotal == 0) 0 else p.batchesDone * 100 / p.batchesTotal
                    },
                    onSave = { novo ->
                        ultimo = novo
                        speakerAiStore.save(identidade, textos, novo)
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                mensagem = getString(R.string.character_ai_cancelled)
            } catch (e: Exception) {
                Log.w("ReaderActivity", "Falha parcial na analise de personagens por IA", e)
                mensagem = getString(
                    R.string.character_ai_partial,
                    if (e is com.jonjonesbr.audiobookgen.domain.AiApiException) e.message.orEmpty()
                    else getString(mensagemDeErro(e))
                )
            }
            dialogo.dismiss()
            if (caminhoArquivo != identidade) return@launch
            val novo = withContext(Dispatchers.IO) { reaplicarAnaliseComIa(identidade, textos, ultimo) }
            if (novo != null) {
                Toast.makeText(
                    activity,
                    mensagem ?: getString(R.string.character_ai_done, novo.speakers.size),
                    Toast.LENGTH_LONG
                ).show()
                mostrarListaVozesPersonagens(novo)
            }
        }
    }

    /** Refaz o mapa de falantes com as correções da IA, mantendo as vozes já escolhidas. */
    private fun reaplicarAnaliseComIa(identidade: String, textos: List<String>, estado: SpeakerAiStore.State): SpeakerAttributionResult? {
        val edicoes = speakerEditsStore.load(identidade, textos)
        if (estado.labels.isEmpty() && edicoes.isEmpty()) return null
        val vozes = speakerAttributionStore.previousVoices(identidade, textos)
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragrafo -> paragrafo.texto } == textos }?.capitulos.orEmpty()
        val novo = OfflineSpeakerAttributor.analyze(textos, aiOverrides = estado.labels, edits = edicoes).let { resultado ->
            if (vozes.isEmpty()) resultado else resultado.copy(
                speakers = resultado.speakers.map { it.copy(voiceId = vozes[it.id] ?: it.voiceId) }
            )
        }
        speakerAttributionStore.save(identidade, textos, novo, capitulos)
        val generos = novo.speakers.associate { it.id to SpeakerGenderInferrer.infer(it.name, textos) }
        activity.runOnUiThread {
            speakerAttribution = novo
            speakerGenders = generos
            guidedPlayerManager?.setSpeakerAttribution(novo)
        }
        return novo
    }

    /** Lista os nomes dos personagens detectados para o usuário ajustar como cada um é falado. */
    private fun mostrarPronunciaDosNomes(result: SpeakerAttributionResult) {
        val dicionario = PronunciationDialog.dicionarioEfetivo(activity, caminhoArquivo)
        val nomes = result.speakers.map { it.name }
        val rotulos = nomes.map { nome -> dicionario.find(nome)?.let { "$nome  →  ${it.spoken}" } ?: nome }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_voices_pronunciation_title)
            .setItems(rotulos.toTypedArray()) { _, position ->
                PronunciationDialog.show(activity, nomes[position], caminhoDoLivro = caminhoArquivo, onOuvir = callbacks.ouvirPronuncia) {
                    mostrarPronunciaDosNomes(result)
                }
            }
            .setPositiveButton(R.string.pron_manage_title) { _, _ ->
                PronunciationDialog.showManager(
                    activity, caminhoArquivo, callbacks.ouvirPronuncia,
                    onSugerirIa = {
                        val paragrafos = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }.orEmpty()
                        PronunciationAiFlow.start(activity, paragrafos, caminhoArquivo)
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun rotuloGenero(speaker: DetectedSpeaker): String = getString(
        when (speakerGenders[speaker.id]) {
            SpeakerGender.MALE -> R.string.character_gender_male
            SpeakerGender.FEMALE -> R.string.character_gender_female
            else -> R.string.character_gender_unknown
        }
    )

    /** Aviso único por livro: só quando há personagens e nenhum tem voz atribuída. */
    private fun avisarPersonagensEncontradosSeNecessario(result: SpeakerAttributionResult) {
        if (result.speakers.isEmpty() || result.speakers.any { !it.voiceId.isNullOrBlank() }) return
        if (activity.isFinishing || activity.isDestroyed || appPrefs.avisoPersonagensVisto(caminhoArquivo)) return
        appPrefs.marcarAvisoPersonagensVisto(caminhoArquivo)
        Snackbar.make(
            activity.findViewById(android.R.id.content),
            getString(R.string.character_voices_hint, result.speakers.size),
            HINT_PERSONAGENS_MS
        ).setAction(R.string.character_voices_hint_action) {
            mostrarListaVozesPersonagens(speakerAttribution ?: result)
        }.show()
    }

    /** Sugere (não aplica) vozes prontas do motor do narrador; o usuário confirma antes de salvar. */
    private fun sugerirVozesPersonagens(result: SpeakerAttributionResult) {
        val manager = guidedPlayerManager ?: return
        val (narratorVoice, motorSalvo) = manager.vozEMotorDoLivro()
        val engine = VoiceCatalog.effectiveEngine(narratorVoice, motorSalvo)
        activity.lifecycleScope.launch {
            val sugestoes = withContext(Dispatchers.IO) {
                val candidatas = vozesParaPersonagens(narratorVoice, engine)
                val idiomaNarrador = (candidatas.firstOrNull { it.id == narratorVoice } ?: VoiceCatalog.findAny(narratorVoice))
                    ?.language
                SpeakerVoiceSuggester.suggest(
                    speakers = result.speakers,
                    genders = speakerGenders,
                    candidates = candidatas,
                    narratorVoiceId = narratorVoice,
                    narratorLanguage = idiomaNarrador,
                    narratorEngine = engine,
                    isReady = ::vozUtilizavelAgora
                )
            }
            val comVoz = sugestoes.filter { it.voice != null }
            if (comVoz.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.character_voices_suggest_title)
                    .setMessage(R.string.character_voices_suggest_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .exibir()
                return@launch
            }
            val resumo = sugestoes.joinToString("\n") { s ->
                getString(
                    R.string.character_voices_suggest_line,
                    s.speaker.name,
                    rotuloGenero(s.speaker),
                    s.voice?.name ?: getString(R.string.character_voices_suggest_no_voice)
                )
            }
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.character_voices_suggest_title)
                .setMessage(getString(R.string.character_voices_suggest_message, resumo))
                .setPositiveButton(R.string.character_voices_suggest_apply) { _, _ ->
                    aplicarVozesPersonagens(result, comVoz.associate { it.speaker.id to it.voice?.id })
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> mostrarListaVozesPersonagens(result) }
                .exibir()
        }
    }

    private fun mostrarOpcoesVozPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val textos = speakerAnalysisParagraphs.orEmpty()
        val edicoes = speakerEditsStore.load(caminhoArquivo, textos)
        val acoes = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.character_voice_choose) to { abrirSeletorVozPersonagem(result, speaker) },
            getString(R.string.character_voice_use_narrator) to { salvarVozPersonagem(result, speaker.id, null) },
            getString(R.string.character_edit_lines) to { mostrarFalasDoPersonagem(result, speaker) },
            getString(R.string.character_edit_merge) to { escolherFusaoDePersonagem(result, speaker) }
        )
        val fundidos = edicoes.aliases.filter { OfflineSpeakerAttributor.idOf(it.value) == speaker.id }.keys
        if (fundidos.isNotEmpty()) {
            acoes += getString(R.string.character_edit_unmerge, fundidos.size) to {
                aplicarEdicaoManual { edits -> fundidos.fold(edits) { acc, id -> acc.withoutMerge(id) } }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(
                getString(
                    R.string.character_voice_assignment_item,
                    speaker.name,
                    speaker.utteranceCount,
                    nomeVozPersonagem(speaker),
                    rotuloGenero(speaker)
                )
            )
            .setItems(acoes.map { it.first }.toTypedArray()) { _, indice -> acoes[indice].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    // ── Correção manual de personagens (B4): conferir falas, corrigir uma fala, fundir nomes ─────────

    private fun mostrarFalasDoPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val textos = speakerAnalysisParagraphs ?: return
        val falas = SpeechLines.of(result, textos, speaker.id)
        if (falas.isEmpty()) {
            Toast.makeText(activity, R.string.character_edit_lines_empty, Toast.LENGTH_LONG).show()
            return
        }
        val rotulos = falas.map { getString(R.string.character_edit_excerpt, it.paragraphIndex + 1, it.text.take(EXCERTO_FALA_MAX)) }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.character_edit_lines_title, speaker.name))
            .setItems(rotulos.toTypedArray()) { _, indice -> escolherQuemFala(result, speaker, falas[indice]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> mostrarOpcoesVozPersonagem(result, speaker) }
            .exibir()
    }

    private fun escolherQuemFala(result: SpeakerAttributionResult, atual: DetectedSpeaker, fala: com.jonjonesbr.audiobookgen.domain.SpeechLine) {
        val outros = result.speakers.filter { it.id != atual.id }
        val opcoes = outros.map { it.name } + getString(R.string.character_edit_narrator) + getString(R.string.character_edit_other)
        // setMessage e setItems não convivem: a fala aparece no título.
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.character_edit_who_title) + "\n" + fala.text.take(EXCERTO_FALA_MAX))
            .setItems(opcoes.toTypedArray()) { _, indice ->
                when {
                    indice < outros.size -> corrigirFala(fala, outros[indice].name)
                    indice == outros.size -> corrigirFala(fala, OfflineSpeakerAttributor.AI_NARRATOR)
                    else -> digitarNomeDaFala(fala)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun digitarNomeDaFala(fala: com.jonjonesbr.audiobookgen.domain.SpeechLine) {
        val campo = android.widget.EditText(activity).apply {
            hint = getString(R.string.character_edit_other_hint)
            setSingleLine()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.character_edit_other)
            .setView(android.widget.FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(campo) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nome = campo.text.toString().trim()
                if (nome.isNotEmpty()) corrigirFala(fala, nome)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun corrigirFala(fala: com.jonjonesbr.audiobookgen.domain.SpeechLine, nome: String) {
        aplicarEdicaoManual { it.withLine(fala.paragraphIndex, fala.ordinal, nome) }
    }

    private fun escolherFusaoDePersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        val outros = result.speakers.filter { it.id != speaker.id }
        if (outros.isEmpty()) {
            Toast.makeText(activity, R.string.character_edit_merge_none, Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.character_edit_merge_title, speaker.name))
            .setItems(outros.map { it.name }.toTypedArray()) { _, indice ->
                val destino = outros[indice]
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setMessage(getString(R.string.character_edit_merge_confirm, speaker.name, destino.name))
                    .setPositiveButton(android.R.string.ok) { _, _ -> aplicarEdicaoManual { it.withMerge(speaker.id, destino.name) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .exibir()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** Grava a correção, refaz a análise (mantendo as vozes já escolhidas) e reabre a lista de personagens. */
    private fun aplicarEdicaoManual(mudanca: (SpeakerEdits) -> SpeakerEdits) {
        val textos = speakerAnalysisParagraphs ?: return
        val identidade = caminhoArquivo
        activity.lifecycleScope.launch {
            val novo = withContext(Dispatchers.IO) {
                speakerEditsStore.save(identidade, textos, mudanca(speakerEditsStore.load(identidade, textos)))
                reaplicarAnaliseComIa(identidade, textos, speakerAiStore.load(identidade, textos))
            }
            if (novo != null && caminhoArquivo == identidade) {
                Toast.makeText(activity, R.string.character_edit_done, Toast.LENGTH_SHORT).show()
                mostrarListaVozesPersonagens(novo)
            }
        }
    }

    private fun nomeVozPersonagem(speaker: DetectedSpeaker): String {
        val voiceId = speaker.voiceId ?: return getString(R.string.character_voice_narrator)
        return VoiceCatalog.findAny(voiceId)?.name
            ?: AndroidVoiceId.decode(voiceId)?.second
            ?: voiceId
    }

    /** Vozes oferecidas aos personagens: TODOS os motores (a síntese resolve o motor de cada voz). */
    private suspend fun vozesParaPersonagens(narratorVoice: String, engine: String): List<VoiceOption> {
        val doNarradorAndroid = if (engine == "android") {
            val packageName = AndroidVoiceId.decode(narratorVoice)?.first
            if (packageName == null) emptyList() else AndroidTtsEngineCache.listVoices(activity, packageName)
        } else {
            emptyList()
        }
        return VoiceCatalog.vozesVisiveis(VoiceCatalog.forVoiceSelection(), activity) + doNarradorAndroid
    }

    /** Voz utilizável agora: pacote local baixado ou, para nuvem com chave, chave configurada. */
    private fun vozUtilizavelAgora(voz: VoiceOption): Boolean = when (voz.engine) {
        "gemini" -> SecurePreferences.getGeminiKeys(activity).isNotBlank()
        "elevenlabs" -> !SecurePreferences.getElevenLabsKey(activity).isNullOrBlank()
        else -> VoiceCatalog.modeloProntoParaVoz(voz.id, activity)
    }

    private fun abrirSeletorVozPersonagem(result: SpeakerAttributionResult, speaker: DetectedSpeaker) {
        // Voz/motor do NARRADOR deste livro ("Voz e velocidade deste livro"), não os globais.
        val manager = guidedPlayerManager ?: return
        val (narratorVoice, motorSalvo) = manager.vozEMotorDoLivro()
        val engine = VoiceCatalog.effectiveEngine(narratorVoice, motorSalvo)
        activity.lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) { vozesParaPersonagens(narratorVoice, engine) }
            if (voices.isEmpty()) {
                Toast.makeText(activity, R.string.character_voices_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            VoiceBottomSheet().apply {
                configure(
                    voices = voices,
                    selectedVoiceId = speaker.voiceId ?: narratorVoice,
                    onVoiceSelected = { voice ->
                        speakerVoiceDownloadFlow.verificarEBaixarSeNecessario(voice) {
                            salvarVozPersonagem(result, speaker.id, voice.id)
                        }
                    },
                    restorable = false
                )
            }.show(activity.supportFragmentManager, "speaker_voice_${speaker.id}")
        }
    }

    private fun salvarVozPersonagem(
        result: SpeakerAttributionResult,
        speakerId: String,
        voiceId: String?
    ) = aplicarVozesPersonagens(result, mapOf(speakerId to voiceId))

    /** Salva de uma vez as vozes de vários personagens (id do personagem → id da voz, null = narrador). */
    private fun aplicarVozesPersonagens(
        result: SpeakerAttributionResult,
        vozesPorPersonagem: Map<String, String?>
    ) {
        val updated = result.copy(
            speakers = result.speakers.map {
                if (it.id in vozesPorPersonagem) it.copy(voiceId = vozesPorPersonagem[it.id]) else it
            }
        )
        val paragraphs = speakerAnalysisParagraphs ?: return
        val capitulos = viewModel.textoEfetivo()
            ?.takeIf { it.paragrafos.map { paragraph -> paragraph.texto } == paragraphs }
            ?.capitulos
            .orEmpty()
        if (!speakerAttributionStore.save(
                caminhoArquivo,
                paragraphs,
                updated,
                capitulos
            )
        ) {
            Log.w("ReaderActivity", "Não foi possível persistir o mapeamento de voz dos personagens")
        }
        speakerAttribution = updated
        guidedPlayerManager?.setSpeakerAttribution(updated)
        Toast.makeText(activity, R.string.character_voice_saved, Toast.LENGTH_SHORT).show()
        mostrarListaVozesPersonagens(updated)
    }
}
