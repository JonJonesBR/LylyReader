package com.jonjonesbr.audiobookgen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
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
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.tts.PocketCustomVoiceCatalog
import com.jonjonesbr.audiobookgen.tts.PocketCustomVoices
import com.jonjonesbr.audiobookgen.tts.PocketEncoderManager
import com.jonjonesbr.audiobookgen.tts.VoiceReferenceAudio
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Criação e gestão de vozes clonadas com o Pocket TTS. Instanciar ANTES de a Activity iniciar (registra
 * os seletores de resultado). A referência (gravada ou de arquivo) só é convertida e guardada no
 * aparelho; o aviso de consentimento aparece sempre antes de qualquer captura.
 */
class CloneVoiceFlow(private val activity: AppCompatActivity, private val aoMudar: () -> Unit = {}) {

    private val handler = Handler(Looper.getMainLooper())
    private var gravador: VoiceReferenceAudio.Gravador? = null

    private val pedirMicrofone =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
            if (concedida) mostrarGravacao() else toast(R.string.clone_mic_denied)
        }

    // Qualquer áudio (mp3, m4a/aac, ogg/opus, wav, flac, amr…) e também vídeos (o som é aproveitado).
    private val escolherArquivo =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::converterArquivo) }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun rascunho() = File(activity.cacheDir, "clone_referencia.wav")

    // ── Passo 1: consentimento ───────────────────────────────────────────────

    fun iniciar() {
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
            .show()
        val continuar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
        continuar.isEnabled = false
        aceite.setOnCheckedChangeListener { _, marcado -> continuar.isEnabled = marcado }
    }

    // ── Passo 2: gravar ou escolher arquivo ──────────────────────────────────

    private fun escolherOrigem() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_source_title)
            .setItems(arrayOf(activity.getString(R.string.clone_source_record), activity.getString(R.string.clone_source_file))) { _, qual ->
                if (qual == 0) gravar() else escolherArquivo.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun gravar() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mostrarGravacao()
        } else {
            pedirMicrofone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun mostrarGravacao() {
        val rec = VoiceReferenceAudio.Gravador()
        if (!rec.iniciar()) {
            toast(R.string.clone_record_failed)
            return
        }
        gravador = rec
        val tempo = TextView(activity).apply { textSize = 22f; gravity = android.view.Gravity.CENTER }
        val nivel = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(activity).apply { setText(R.string.clone_record_hint); textSize = 13f })
            addView(tempo)
            addView(nivel)
        }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_record_title)
            .setView(conteudo)
            .setCancelable(false)
            .setPositiveButton(R.string.clone_record_stop, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> rec.cancelar(); gravador = null }
            .create()
        dialogo.show()
        val atualizar = object : Runnable {
            override fun run() {
                tempo.text = String.format("%.1f s", rec.segundos)
                nivel.progress = (rec.nivel * 100).toInt()
                if (rec.terminouSozinho) {
                    dialogo.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                } else if (dialogo.isShowing) {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(atualizar)
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val resultado = rec.parar(rascunho())
            gravador = null
            dialogo.dismiss()
            depoisDaCaptura(resultado?.seconds)
        }
    }

    private fun converterArquivo(uri: Uri) {
        activity.lifecycleScope.launch {
            val segundos = withContext(Dispatchers.IO) {
                runCatching { VoiceReferenceAudio.converterArquivo(activity, uri, rascunho()).seconds }.getOrNull()
            }
            depoisDaCaptura(segundos)
        }
    }

    // ── Passo 3: nome e idioma ───────────────────────────────────────────────

    private fun depoisDaCaptura(segundos: Float?) {
        if (segundos == null) {
            toast(R.string.clone_audio_failed)
            return
        }
        if (segundos < PocketCustomVoices.MIN_SECONDS) {
            toast(R.string.clone_audio_too_short)
            return
        }
        val nome = EditText(activity).apply {
            hint = activity.getString(R.string.clone_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val idiomas = listOf("pt-BR" to R.string.clone_lang_pt, "en-US" to R.string.clone_lang_en, "es-ES" to R.string.clone_lang_es)
        val grupo = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            val padrao = when (activity.resources.configuration.locales[0].language) {
                "en" -> "en-US"
                "es" -> "es-ES"
                else -> "pt-BR"
            }
            idiomas.forEachIndexed { i, (tag, res) ->
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
            addView(TextView(activity).apply { setText(R.string.clone_lang_label); setPadding(0, dp(12), 0, 0) })
            addView(grupo)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_name_title)
            .setView(conteudo)
            .setPositiveButton(R.string.clone_create) { _, _ ->
                val tag = grupo.findViewById<RadioButton>(grupo.checkedRadioButtonId)?.tag as? String ?: "pt-BR"
                criar(nome.text.toString(), tag)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> rascunho().delete() }
            .show()
    }

    // ── Passo 4: criar a voz e baixar o que falta ────────────────────────────

    private fun criar(nome: String, idioma: String) {
        activity.lifecycleScope.launch {
            val voz = withContext(Dispatchers.IO) {
                runCatching { PocketCustomVoices.add(activity, nome, idioma, rascunho()) }.getOrNull()
            }
            rascunho().delete()
            if (voz == null) {
                toast(R.string.clone_audio_failed)
                return@launch
            }
            PocketCustomVoiceCatalog.registrarTodas(activity)
            aoMudar()
            baixarSeNecessario(voz.id)
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
            .setView(conteudo).setCancelable(false).show()
        activity.lifecycleScope.launch {
            val erro = runCatching {
                com.jonjonesbr.audiobookgen.service.DownloadCentral.baixarEAguardar(
                    activity.applicationContext, pacote.id
                ) { nomeItem, pct, _, _ ->
                    activity.runOnUiThread { barra.progress = (pct * 100).toInt(); texto.text = nomeItem }
                }
            }.exceptionOrNull()
            dialogo.dismiss()
            PocketCustomVoiceCatalog.registrarTodas(activity)
            aoMudar()
            if (erro == null) {
                toast(R.string.clone_created)
            } else {
                Toast.makeText(activity, activity.getString(R.string.clone_download_failed, erro.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Gestão ───────────────────────────────────────────────────────────────

    fun gerenciar() {
        val vozes = PocketCustomVoices.list(activity)
        val construtor = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_manage_title)
            .setPositiveButton(R.string.clone_new) { _, _ -> iniciar() }
            .setNegativeButton(R.string.pron_close, null)
        if (vozes.isEmpty()) {
            construtor.setMessage(R.string.clone_manage_empty)
        } else {
            construtor.setItems(vozes.map { "${it.name}  ·  ${it.languageTag}" }.toTypedArray()) { _, qual ->
                opcoesDaVoz(vozes[qual])
            }
        }
        construtor.show()
    }

    private fun opcoesDaVoz(voz: PocketCustomVoices.Voice) {
        AlertDialog.Builder(activity)
            .setTitle(voz.name)
            .setItems(arrayOf(activity.getString(R.string.clone_rename), activity.getString(R.string.clone_delete))) { _, qual ->
                if (qual == 0) renomear(voz) else apagar(voz)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> gerenciar() }
            .show()
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
            .show()
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
            .show()
    }
}
