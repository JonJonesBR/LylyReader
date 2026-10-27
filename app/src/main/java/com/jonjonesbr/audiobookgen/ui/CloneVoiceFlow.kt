package com.jonjonesbr.audiobookgen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.Avaliacao
import com.jonjonesbr.audiobookgen.domain.Aviso
import com.jonjonesbr.audiobookgen.domain.Faixa
import com.jonjonesbr.audiobookgen.domain.avaliar
import com.jonjonesbr.audiobookgen.domain.classificarProgresso
import com.jonjonesbr.audiobookgen.tts.PocketCustomVoiceCatalog
import com.jonjonesbr.audiobookgen.tts.PocketCustomVoices
import com.jonjonesbr.audiobookgen.tts.PocketEncoderManager
import com.jonjonesbr.audiobookgen.tts.VoiceReferenceAudio
import com.jonjonesbr.audiobookgen.util.TextoComMarcacao
import com.jonjonesbr.audiobookgen.util.TipoTrecho
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import android.util.Log
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Meta de duração da gravação guiada: a barra de progresso chega cheia aqui (a gravação vai até 30 s). */
private const val META_GRAVACAO_S = 15

/**
 * Criação e gestão de vozes clonadas com o Pocket TTS. Instanciar ANTES de a Activity iniciar (registra
 * os seletores de resultado). A referência (gravada ou de arquivo) só é convertida e guardada no
 * aparelho; o aviso de consentimento aparece sempre antes de qualquer captura.
 */
class CloneVoiceFlow(private val activity: AppCompatActivity, private val aoMudar: () -> Unit = {}) {

    private val handler = Handler(Looper.getMainLooper())
    private val dialogos = DialogosAbertos(activity)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)
    private var gravador: VoiceReferenceAudio.Gravador? = null
    private var dialogoGravacao: AlertDialog? = null
    /** Idioma do texto-guia escolhido antes de gravar. Vale para a gravação que está por vir. */
    private var idiomaGravacao = "pt-BR"

    init {
        // Sem isto, o Runnable de 100 ms da gravação continuaria agendado depois que a Activity morre.
        // Activity destruída no meio da gravação (ex.: girar a tela): para o gravador e fecha o diálogo.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                gravador?.cancelar()
                gravador = null
                dialogoGravacao?.dismiss()
                dialogoGravacao = null
            }
        })
    }

    private val pedirMicrofone =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
            if (concedida) mostrarGravacao() else toast(R.string.clone_mic_denied)
        }

    // Qualquer áudio (mp3, m4a/aac, ogg/opus, wav, flac, amr…) e também vídeos (o som é aproveitado).
    private val escolherArquivo =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::converterArquivo) }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()

    /**
     * Roda [trabalho] fora da tela principal mostrando "Aguarde…" com [mensagem]: converter o arquivo ou salvar a
     * gravação leva alguns segundos, e sem aviso parecia que o app tinha travado entre uma tela e outra.
     */
    private suspend fun <T> comAguarde(mensagem: Int, trabalho: suspend () -> T): T {
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            addView(ProgressBar(activity).apply { isIndeterminate = true })
            addView(TextView(activity).apply {
                setText(mensagem)
                textSize = 15f
                setPadding(dp(16), 0, 0, 0)
            })
        }
        val dialogo = AlertDialog.Builder(activity).setView(conteudo).setCancelable(false).exibir()
        return try {
            withContext(Dispatchers.IO) { trabalho() }
        } finally {
            dialogo.dismiss()
        }
    }
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun rascunho() = File(activity.cacheDir, "clone_referencia.wav")

    // Arquivo oficial da Kyutai baixado pelo navegador: o seletor já abre na pasta Downloads.
    private val escolherArquivoKyutai: androidx.activity.result.ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(AbrirNosDownloads()) { uri -> assistente.aoEscolherArquivo(uri) }

    private val assistente: ClonagemAssistente =
        ClonagemAssistente(activity) { escolherArquivoKyutai.launch(arrayOf("*/*")) }

    private class AbrirNosDownloads : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
            super.createIntent(context, input).apply {
                putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads")
                )
            }
    }

    // ── Passo 1: consentimento ───────────────────────────────────────────────

    fun iniciar() {
        // Versões de teste guardavam um token do Hugging Face; a clonagem não usa mais login nenhum.
        SecurePreferences.apagarTokenHfLegado(activity)
        if (!PocketEncoderManager.algumInstalado(activity)) {
            assistente.mostrar { iniciar() }
            return
        }
        val aceite = CheckBox(activity).apply { setText(R.string.clone_consent_check) }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(activity).apply { setText(R.string.clone_consent_message); textSize = 14f })
            addView(aceite)
        }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_consent_title)
            .setView(conteudo)
            .setPositiveButton(R.string.clone_continue) { _, _ -> escolherOrigem() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
        val continuar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
        continuar.isEnabled = false
        aceite.setOnCheckedChangeListener { _, marcado -> continuar.isEnabled = marcado }
    }

    // ── Passo 2: gravar ou escolher arquivo ──────────────────────────────────

    private fun escolherOrigem() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_source_title)
            .setItems(arrayOf(activity.getString(R.string.clone_source_record), activity.getString(R.string.clone_source_file))) { _, qual ->
                if (qual == 0) escolherIdiomaGravacao() else escolherArquivo.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** Idioma do texto-guia vem antes da gravação: é nele que o usuário vai ler. */
    private fun escolherIdiomaGravacao() {
        val tags = arrayOf("pt-BR", "en-US", "es-ES")
        val nomes = arrayOf(
            activity.getString(R.string.clone_lang_pt),
            activity.getString(R.string.clone_lang_en),
            activity.getString(R.string.clone_lang_es),
        )
        var escolhido = tags.indexOf(idiomaPadrao())
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_lang_label)
            .setSingleChoiceItems(nomes, escolhido) { _, qual -> escolhido = qual }
            .setPositiveButton(R.string.clone_continue) { _, _ ->
                idiomaGravacao = tags[escolhido]
                gravar()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    private fun idiomaPadrao(): String = when (activity.resources.configuration.locales[0].language) {
        "en" -> "en-US"
        "es" -> "es-ES"
        else -> "pt-BR"
    }

    private fun gravar() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mostrarGravacao()
        } else {
            pedirMicrofone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Gravação guiada: o usuário lê o texto do idioma escolhido, vê o progresso até a meta e a qualidade.
     * Para sozinha aos 30 s. Ao parar, avalia o áudio antes de salvar e mostra avisos, se houver.
     */
    private fun mostrarGravacao() {
        val rec = VoiceReferenceAudio.Gravador()
        if (!rec.iniciar()) {
            toast(R.string.clone_record_failed)
            return
        }
        gravador = rec
        val vista = LayoutInflater.from(activity).inflate(R.layout.dialog_clone_gravacao, FrameLayout(activity), false)
        val btnDicas = vista.findViewById<Button>(R.id.clone_btn_dicas)
        val blocoDicas = vista.findViewById<View>(R.id.clone_gravacao_dicas)
        val guia = vista.findViewById<TextView>(R.id.clone_guia_texto)
        val tempo = vista.findViewById<TextView>(R.id.clone_tempo)
        val meta = vista.findViewById<TextView>(R.id.clone_meta)
        val barra = vista.findViewById<ProgressBar>(R.id.clone_barra_meta)
        val nivel = vista.findViewById<ProgressBar>(R.id.clone_nivel)
        val status = vista.findViewById<TextView>(R.id.clone_status)
        val btnRegravar = vista.findViewById<Button>(R.id.clone_btn_regravar)
        val btnParar = vista.findViewById<Button>(R.id.clone_btn_parar)
        val btnCancelar = vista.findViewById<Button>(R.id.clone_btn_cancelar)

        guia.text = montarTextoGuia(recursoDoTextoGuia(idiomaGravacao))
        meta.text = activity.getString(R.string.clone_record_goal, META_GRAVACAO_S)
        status.setText(R.string.clone_record_status_short)
        btnDicas.setOnClickListener {
            blocoDicas.visibility = View.VISIBLE
            btnDicas.visibility = View.GONE
        }

        val dialogo = AlertDialog.Builder(activity)
            .setView(vista)
            .setCancelable(false)
            .create()
        // Só uma conclusão (parar, tempo esgotado ou toque repetido) processa a gravação.
        var concluida = false

        fun concluir() {
            if (concluida) return
            concluida = true
            rec.pararCaptura()
            gravador = null
            val amostras = rec.copiaAmostras()
            val taxa = rec.taxaAmostragem
            dialogo.dismiss()
            if (amostras.size < 2) {
                toast(R.string.clone_audio_failed)
                return
            }
            activity.lifecycleScope.launch {
                val avaliacao = comAguarde(R.string.clone_aguarde_analisando) { avaliar(amostras, taxa) }
                if (avaliacao.segundos < PocketCustomVoices.MIN_RECORD_SECONDS) {
                    avisarCurto(PocketCustomVoices.MIN_RECORD_SECONDS)
                    return@launch
                }
                if (avaliacao.boa) {
                    finalizarGravacao(amostras, taxa)
                } else {
                    mostrarAvisos(avaliacao, usar = { finalizarGravacao(amostras, taxa) }, regravar = { mostrarGravacao() })
                }
            }
        }

        fun cancelar() {
            if (concluida) return
            concluida = true
            rec.cancelar()
            gravador = null
            dialogo.dismiss()
        }

        val atualizar = object : Runnable {
            override fun run() {
                if (concluida || !dialogo.isShowing) return
                // A gravação parou sozinha (30 s): conclui pelo mesmo caminho do botão Parar.
                if (rec.terminouSozinho) {
                    concluir()
                    return
                }
                val segundos = rec.segundos
                val faixa = classificarProgresso(segundos)
                tempo.text = String.format(java.util.Locale.getDefault(), "%.1f s", segundos)
                barra.progress = (minOf(segundos, META_GRAVACAO_S.toFloat()) * 10).toInt()
                barra.progressTintList = ColorStateList.valueOf(corDaFaixa(faixa))
                nivel.progress = (rec.nivel * 100).toInt()
                status.setText(textoDoStatus(faixa, rec.nivel))
                btnParar.isEnabled = segundos >= PocketCustomVoices.MIN_RECORD_SECONDS
                handler.postDelayed(this, 100)
            }
        }

        btnParar.setOnClickListener { concluir() }
        btnCancelar.setOnClickListener { cancelar() }
        btnRegravar.setOnClickListener {
            if (concluida) return@setOnClickListener
            if (!rec.reiniciar()) {
                concluida = true
                gravador = null
                dialogo.dismiss()
                toast(R.string.clone_record_failed)
            }
        }
        // Fechou o diálogo (parar, cancelar ou tempo esgotado): para o Runnable de 100 ms e esquece o diálogo.
        dialogo.setOnDismissListener {
            handler.removeCallbacks(atualizar)
            if (dialogoGravacao === dialogo) dialogoGravacao = null
        }
        dialogoGravacao = dialogo
        dialogos.mostrar(dialogo)
        handler.post(atualizar)
    }

    private fun finalizarGravacao(amostras: ShortArray, taxa: Int) {
        activity.lifecycleScope.launch {
            val segundos = comAguarde(R.string.clone_aguarde_carregando) {
                runCatching { VoiceReferenceAudio.salvar(amostras, taxa, rascunho()).seconds }.getOrNull()
            }
            depoisDaCaptura(segundos, PocketCustomVoices.MIN_RECORD_SECONDS, idiomaGravacao)
        }
    }

    /** Lista os avisos de qualidade. "Usar assim mesmo" salva; "Regravar" abre a gravação de novo. */
    private fun mostrarAvisos(avaliacao: Avaliacao, usar: () -> Unit, regravar: () -> Unit) {
        val linhas = avaliacao.avisos.joinToString("\n") { "• " + activity.getString(textoDoAviso(it)) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_quality_title)
            .setMessage(linhas)
            .setPositiveButton(R.string.clone_quality_use_anyway) { _, _ -> usar() }
            .setNegativeButton(R.string.clone_quality_redo) { _, _ -> regravar() }
            .exibir()
    }

    private fun converterArquivo(uri: Uri) {
        activity.lifecycleScope.launch {
            val segundos = comAguarde(R.string.clone_aguarde_carregando) {
                runCatching { VoiceReferenceAudio.converterArquivo(activity, uri, rascunho()).seconds }.getOrNull()
            }
            depoisDaCaptura(segundos, PocketCustomVoices.MIN_SECONDS, null)
        }
    }

    // ── Passo 3: nome e idioma ───────────────────────────────────────────────

    /** [idiomaFixo] não é nulo quando o idioma já foi escolhido antes de gravar; senão o usuário escolhe aqui. */
    private fun depoisDaCaptura(segundos: Float?, minimo: Int, idiomaFixo: String?) {
        if (segundos == null) {
            toast(R.string.clone_audio_failed)
            return
        }
        if (segundos < minimo) {
            avisarCurto(minimo)
            return
        }
        val nome = EditText(activity).apply {
            hint = activity.getString(R.string.clone_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val idiomas = listOf("pt-BR" to R.string.clone_lang_pt, "en-US" to R.string.clone_lang_en, "es-ES" to R.string.clone_lang_es)
        val grupo: RadioGroup? = if (idiomaFixo != null) null else RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            val padrao = idiomaPadrao()
            idiomas.forEachIndexed { _, (tag, res) ->
                addView(RadioButton(activity).apply { id = View.generateViewId(); setText(res); this.tag = tag; isChecked = tag == padrao })
            }
        }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.clone_audio_ok, segundos)
                textSize = 13f
            })
            addView(nome)
            if (grupo != null) {
                addView(TextView(activity).apply { setText(R.string.clone_lang_label); setPadding(0, dp(12), 0, 0) })
                addView(grupo)
            }
        }
        // Cancelar, voltar ou tocar fora apaga a gravação. "Criar" a usa logo depois, então não pode apagar nesse caso.
        var criando = false
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_name_title)
            .setView(conteudo)
            .setPositiveButton(R.string.clone_create) { _, _ ->
                criando = true
                val tag = idiomaFixo
                    ?: grupo?.let { g -> g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as? String }
                    ?: "pt-BR"
                criar(nome.text.toString(), tag)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!criando) rascunho().delete() }
            .exibir()
    }

    private fun avisarCurto(minimo: Int) {
        Toast.makeText(activity, activity.getString(R.string.clone_audio_too_short, minimo), Toast.LENGTH_LONG).show()
    }

    // ── Passo 4: criar a voz e baixar o que falta ────────────────────────────

    private fun criar(nome: String, idioma: String) {
        activity.lifecycleScope.launch {
            val voz = comAguarde(R.string.clone_aguarde_criando) {
                runCatching { PocketCustomVoices.add(activity, nome, idioma, rascunho()) }.getOrNull()
            }
            rascunho().delete()
            if (voz == null) {
                toast(R.string.clone_audio_failed)
                return@launch
            }
            withContext(Dispatchers.IO) { PocketCustomVoiceCatalog.registrarTodas(activity) }
            aoMudar()
            if (PocketEncoderManager.isInstalled(activity, idioma)) {
                baixarSeNecessario(voz.id)
            } else {
                // A voz já está salva; falta liberar a clonagem neste idioma.
                assistente.mostrar(idioma) { baixarSeNecessario(voz.id) }
            }
        }
    }

    private fun baixarSeNecessario(voiceId: String) {
        val pacote = VoiceCatalog.pacoteDaVoz(voiceId)
        if (pacote == null || pacote.isPronto(activity)) {
            toast(R.string.clone_created)
            return
        }
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val texto = TextView(activity).apply { setText(R.string.clone_downloading) }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
            addView(texto)
            addView(barra)
        }
        val dialogo = AlertDialog.Builder(activity).setTitle(R.string.clone_downloading_title)
            .setView(conteudo).setCancelable(false).exibir()
        activity.lifecycleScope.launch {
            val erro = try {
                runCatching {
                    com.jonjonesbr.audiobookgen.service.DownloadCentral.baixarEAguardar(
                        activity.applicationContext, pacote.id
                    ) { nomeItem, pct, _, _ ->
                        activity.runOnUiThread { barra.progress = (pct * 100).toInt(); texto.text = nomeItem }
                    }
                }.onFailure { if (it is CancellationException) throw it }.exceptionOrNull()
            } finally {
                dialogo.dismiss()
            }
            withContext(Dispatchers.IO) { PocketCustomVoiceCatalog.registrarTodas(activity) }
            aoMudar()
            if (erro == null) {
                toast(R.string.clone_created)
            } else {
                Log.w("CloneVoiceFlow", "Falha ao baixar voz clonada", erro)
                Toast.makeText(activity, activity.getString(R.string.clone_download_failed, activity.getString(mensagemDeErro(erro))), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Gestão ───────────────────────────────────────────────────────────────

    fun gerenciar() {
        val vozes = PocketCustomVoices.list(activity)
        val construtor = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_manage_title)
            .setPositiveButton(R.string.clone_new) { _, _ -> iniciar() }
            .setNeutralButton(R.string.clone_prep_manage) { _, _ -> assistente.mostrar() }
            .setNegativeButton(R.string.pron_close, null)
        if (vozes.isEmpty()) {
            construtor.setMessage(R.string.clone_manage_empty)
        } else {
            construtor.setItems(vozes.map { "${it.name}  ·  ${it.languageTag}" }.toTypedArray()) { _, qual ->
                opcoesDaVoz(vozes[qual])
            }
        }
        construtor.exibir()
    }

    private fun opcoesDaVoz(voz: PocketCustomVoices.Voice) {
        AlertDialog.Builder(activity)
            .setTitle(voz.name)
            .setItems(arrayOf(activity.getString(R.string.clone_rename), activity.getString(R.string.clone_delete))) { _, qual ->
                if (qual == 0) renomear(voz) else apagar(voz)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> gerenciar() }
            .exibir()
    }

    private fun renomear(voz: PocketCustomVoices.Voice) {
        val campo = EditText(activity).apply { setText(voz.name); setSingleLine() }
        val moldura = LinearLayout(activity).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_rename)
            .setView(moldura)
            .setPositiveButton(R.string.pron_save) { _, _ ->
                PocketCustomVoices.rename(activity, voz.id, campo.text.toString())
                VoiceCatalog.removerPacoteLocal(voz.id)
                PocketCustomVoiceCatalog.registrarTodas(activity)
                aoMudar()
                gerenciar()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> gerenciar() }
            .exibir()
    }

    private fun apagar(voz: PocketCustomVoices.Voice) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_delete)
            .setMessage(activity.getString(R.string.clone_delete_message, voz.name))
            .setPositiveButton(R.string.clone_delete) { _, _ ->
                PocketCustomVoices.remove(activity, voz.id)
                VoiceCatalog.removerPacoteLocal(voz.id)
                aoMudar()
                gerenciar()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> gerenciar() }
            .exibir()
    }

    // ── Texto-guia e cores ───────────────────────────────────────────────────

    /** Negrito e dica de entonação são só realce: o texto continua inteiro e legível sem eles. */
    private fun montarTextoGuia(recurso: Int): CharSequence {
        val texto = SpannableStringBuilder()
        val corDica = corSecundaria()
        for (trecho in TextoComMarcacao.analisar(activity.getString(recurso))) {
            val inicio = texto.length
            texto.append(trecho.texto)
            val fim = texto.length
            if (trecho.tipo == TipoTrecho.NEGRITO) {
                texto.setSpan(StyleSpan(Typeface.BOLD), inicio, fim, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (trecho.tipo == TipoTrecho.DICA) {
                texto.setSpan(StyleSpan(Typeface.ITALIC), inicio, fim, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                texto.setSpan(ForegroundColorSpan(corDica), inicio, fim, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return texto
    }

    private fun corSecundaria(): Int {
        val valor = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.textColorSecondary, valor, true)
        return if (valor.resourceId != 0) ContextCompat.getColor(activity, valor.resourceId) else valor.data
    }

    private fun corDaFaixa(faixa: Faixa): Int = when (faixa) {
        Faixa.INSUFICIENTE -> "#EF6C00".toColorInt()
        Faixa.OK -> "#9CCC65".toColorInt()
        Faixa.OTIMO -> "#43A047".toColorInt()
    }
}

private fun recursoDoTextoGuia(tag: String): Int = when (tag) {
    "en-US" -> R.string.clone_guide_text_en
    "es-ES" -> R.string.clone_guide_text_es
    else -> R.string.clone_guide_text_pt
}

private fun textoDoAviso(aviso: Aviso): Int = when (aviso) {
    Aviso.CURTA -> R.string.clone_quality_short
    Aviso.BAIXA -> R.string.clone_quality_low
    Aviso.ESTOUROU -> R.string.clone_quality_clipped
    Aviso.MUITO_SILENCIO -> R.string.clone_quality_silent
    Aviso.RUIDOSA -> R.string.clone_quality_noisy
}

/** Abaixo de 5 s: se o nível está muito baixo, pede para falar mais perto; senão diz que ainda está curto. */
private fun textoDoStatus(faixa: Faixa, nivelPico: Float): Int = when (faixa) {
    Faixa.INSUFICIENTE -> if (nivelPico < 0.02f) R.string.clone_record_status_low else R.string.clone_record_status_short
    Faixa.OK -> R.string.clone_record_status_ok
    Faixa.OTIMO -> R.string.clone_record_status_great
}

/**
 * Guarda os diálogos abertos por um fluxo e fecha todos quando a Activity é destruída (ex.: girar a tela com
 * um diálogo aberto), para não vazar a janela (WindowLeaked). Não usa setOnDismissListener, porque o builder
 * ou o diálogo já podem ter um; os já fechados saem da lista na próxima abertura.
 */
internal class DialogosAbertos(activity: AppCompatActivity) {

    private val abertos = mutableListOf<AlertDialog>()

    // Fraca: este objeto não deve segurar a Activity; usada só para saber se ela já foi destruída.
    private val activityRef = java.lang.ref.WeakReference(activity)

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                abertos.toList().forEach { if (it.isShowing) it.dismiss() }
                abertos.clear()
            }
        })
    }

    fun mostrar(builder: AlertDialog.Builder): AlertDialog = mostrar(builder.create())

    fun mostrar(dialogo: AlertDialog): AlertDialog {
        val activity = activityRef.get()
        if (activity == null || activity.isDestroyed) return dialogo // Activity já destruída: não exibe (evita WindowLeaked)
        abertos.removeAll { !it.isShowing }
        dialogo.show()
        abertos.add(dialogo)
        return dialogo
    }
}
