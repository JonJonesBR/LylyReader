package com.jonjonesbr.audiobookgen.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.PocketEncoderOficial
import com.jonjonesbr.audiobookgen.tts.PocketEncoderImportador
import com.jonjonesbr.audiobookgen.tts.PocketEncoderManager
import kotlinx.coroutines.launch

/**
 * Assistente que leva uma pessoa leiga, passo a passo, a liberar a clonagem de voz:
 * conta gratuita no Hugging Face → aceite dos termos da Kyutai → download do arquivo oficial no
 * navegador → importação para o app. O login acontece só no navegador; o app não vê nem guarda
 * senha, token ou conta. [escolherArquivo] abre o seletor de arquivos (registrado pela Activity).
 */
class ClonagemAssistente(
    private val activity: AppCompatActivity,
    private val escolherArquivo: () -> Unit
) {
    private var idiomaDesejado: String? = null
    // Definido em mostrar(): o assistente é criado junto com a Activity, antes de os recursos existirem.
    private var idiomaDoDownload: String = "pt-BR"
    private var aoConcluir: () -> Unit = {}

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun idiomaDoApp(): String = when (activity.resources.configuration.locales[0].language) {
        "en" -> "en-US"
        "es" -> "es-ES"
        else -> "pt-BR"
    }

    private fun nomeDoIdioma(tag: String): String = activity.getString(
        when (tag.substringBefore('-')) {
            "en" -> R.string.clone_lang_en
            "es" -> R.string.clone_lang_es
            else -> R.string.clone_lang_pt
        }
    )

    /** Começa pelo início. Com [idioma], o passo do download já vem nele e o fim confere se bateu. */
    fun mostrar(idioma: String? = null, depois: () -> Unit = {}) {
        idiomaDesejado = idioma
        idiomaDoDownload = idioma ?: idiomaDoApp()
        aoConcluir = depois
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_title_intro)
            .setView(texto(activity.getString(R.string.clone_prep_intro)))
            .setPositiveButton(R.string.clone_prep_start) { _, _ -> passoConta() }
            .setNegativeButton(R.string.clone_prep_later, null)
            .show()
    }

    private fun passoConta() {
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_step1_title)
            .setView(texto(activity.getString(R.string.clone_prep_step1)))
            .setPositiveButton(R.string.clone_prep_have_account) { _, _ -> passoTermos() }
            .setNeutralButton(R.string.clone_prep_create_account, null)
            .setNegativeButton(R.string.clone_prep_later, null)
            .show()
        // Abrir o site não fecha o passo: a pessoa volta e toca em "Já tenho conta".
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { abrir(PocketEncoderOficial.PAGINA_CONTA) }
    }

    private fun passoTermos() {
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_step2_title)
            .setView(texto(activity.getString(R.string.clone_prep_step2)))
            .setPositiveButton(R.string.clone_prep_accepted) { _, _ -> passoBaixar() }
            .setNeutralButton(R.string.clone_prep_open_terms, null)
            .setNegativeButton(R.string.clone_prep_back) { _, _ -> passoConta() }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { abrir(PocketEncoderOficial.PAGINA_TERMOS) }
    }

    private fun passoBaixar() {
        val idiomas = PocketEncoderOficial.ARQUIVOS.map { it.idioma }
        val grupo = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(8), 0, 0)
            idiomas.forEach { tag ->
                addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = nomeDoIdioma(tag)
                    this.tag = tag
                    textSize = 16f
                    isChecked = tag == idiomaDoDownload
                })
            }
            setOnCheckedChangeListener { g, id -> (g.findViewById<RadioButton>(id)?.tag as? String)?.let { idiomaDoDownload = it } }
        }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_step3_title)
            .setView(texto(activity.getString(R.string.clone_prep_step3), grupo))
            .setPositiveButton(R.string.clone_prep_downloaded) { _, _ -> passoTrazer() }
            .setNeutralButton(R.string.clone_prep_download, null)
            .setNegativeButton(R.string.clone_prep_back) { _, _ -> passoTermos() }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            abrir(PocketEncoderOficial.porIdioma(idiomaDoDownload).urlDownload)
        }
    }

    private fun passoTrazer() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_step4_title)
            .setView(texto(activity.getString(R.string.clone_prep_step4)))
            .setPositiveButton(R.string.clone_prep_pick) { _, _ -> escolherArquivo() }
            .setNegativeButton(R.string.clone_prep_back) { _, _ -> passoBaixar() }
            .show()
    }

    /** Chamado pela Activity quando a pessoa escolhe (ou não) o arquivo no seletor. */
    fun aoEscolherArquivo(uri: Uri?) {
        if (uri == null) {
            passoTrazer()
            return
        }
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_working_title)
            .setView(texto(activity.getString(R.string.clone_prep_working), barra))
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val resultado = runCatching {
                PocketEncoderImportador.importar(activity.applicationContext, uri) { pct ->
                    activity.runOnUiThread { barra.progress = (pct * 100).toInt() }
                }
            }.getOrElse { PocketEncoderImportador.Resultado.Falha(it.message ?: it.javaClass.simpleName) }
            dialogo.dismiss()
            when (resultado) {
                is PocketEncoderImportador.Resultado.Instalado -> concluido(uri, resultado.arquivo.idioma)
                PocketEncoderImportador.Resultado.ArquivoErrado -> erro(activity.getString(R.string.clone_prep_wrong))
                is PocketEncoderImportador.Resultado.Falha ->
                    erro(activity.getString(R.string.clone_prep_failed, resultado.motivo))
            }
        }
    }

    private fun concluido(uri: Uri, idioma: String) {
        val desejado = idiomaDesejado
        if (desejado != null && !PocketEncoderManager.isInstalled(activity, desejado)) {
            // Instalou outro idioma (fica valendo), mas a voz é em outro: volta ao passo do download.
            idiomaDoDownload = desejado
            AlertDialog.Builder(activity)
                .setTitle(R.string.clone_prep_done_title)
                .setView(texto(activity.getString(R.string.clone_prep_other_language, nomeDoIdioma(idioma), nomeDoIdioma(desejado))))
                .setPositiveButton(R.string.clone_prep_back) { _, _ -> passoBaixar() }
                .setNegativeButton(R.string.clone_prep_later, null)
                .show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_done_title)
            .setView(texto(activity.getString(R.string.clone_prep_done, nomeDoIdioma(idioma))))
            .setCancelable(false)
            .setPositiveButton(R.string.clone_prep_delete_big) { _, _ ->
                apagarArquivoGrande(uri)
                aoConcluir()
            }
            .setNegativeButton(R.string.clone_prep_keep) { _, _ -> aoConcluir() }
            .show()
    }

    private fun erro(mensagem: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clone_prep_wrong_title)
            .setView(texto(mensagem))
            .setPositiveButton(R.string.clone_prep_try_again) { _, _ -> passoTrazer() }
            .setNeutralButton(R.string.clone_prep_back) { _, _ -> passoBaixar() }
            .setNegativeButton(R.string.clone_prep_later, null)
            .show()
    }

    private fun apagarArquivoGrande(uri: Uri) {
        val apagou = runCatching { DocumentsContract.deleteDocument(activity.contentResolver, uri) }.getOrDefault(false)
        Toast.makeText(
            activity,
            if (apagou) R.string.clone_prep_deleted else R.string.clone_prep_delete_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun abrir(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.clone_prep_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun texto(mensagem: String, extra: View? = null): View {
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
            addView(TextView(activity).apply {
                text = mensagem
                textSize = 15f
                setLineSpacing(0f, 1.15f)
            })
            extra?.let { addView(it) }
        }
        return ScrollView(activity).apply { addView(conteudo) }
    }
}
