package com.jonjonesbr.audiobookgen.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.ClonagemAcessoHf
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.ClonagemAcessoRegras
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem
import kotlinx.coroutines.launch

/**
 * Passo a passo para liberar a clonagem: o usuário aceita os termos da Kyutai e do repositório dos
 * codificadores na PRÓPRIA conta do Hugging Face, cria um token de leitura e o cola aqui. O app só
 * confere o acesso (identidade e arquivos restritos); não aceita nada no lugar do usuário.
 */
class ClonagemAcessoDialog(private val activity: AppCompatActivity) {

    private val prefs = AppPrefs(activity)

    private fun dp(valor: Int) = (valor * activity.resources.displayMetrics.density).toInt()

    /** Abre o passo a passo. [aoLiberar] roda quando o acesso fica verificado. */
    fun mostrar(aoLiberar: () -> Unit = {}) {
        val conectado = SecurePreferences.getHfTokenClonagem(activity).isNotBlank() && prefs.clonagemAcessoVerificado
        val status = TextView(activity).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
            text = if (conectado) {
                activity.getString(R.string.clone_access_status_ok, nomeDaConta())
            } else {
                activity.getString(R.string.clone_access_status_none)
            }
        }
        val campo = EditText(activity).apply {
            hint = activity.getString(R.string.clone_access_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val progresso = ProgressBar(activity).apply { visibility = View.GONE }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(texto(R.string.clone_access_intro))
            addView(texto(R.string.clone_access_step1))
            addView(botao(R.string.clone_access_btn_kyutai) { abrir(ClonagemAcessoHf.PAGINA_KYUTAI) })
            addView(texto(R.string.clone_access_step2))
            addView(botao(R.string.clone_access_btn_encoders) { abrir(ClonagemAcessoHf.PAGINA_CODIFICADORES) })
            addView(texto(R.string.clone_access_step3))
            addView(botao(R.string.clone_access_btn_token) { abrir(ClonagemAcessoHf.PAGINA_TOKENS) })
            addView(texto(R.string.clone_access_step4))
            addView(campo)
            addView(progresso)
            addView(status)
        }
        val construtor = AlertDialog.Builder(activity)
            .setTitle(R.string.clone_access_title)
            .setView(ScrollView(activity).apply { addView(conteudo) })
            .setPositiveButton(R.string.clone_access_verify, null)
            .setNegativeButton(R.string.pron_close, null)
        if (conectado) construtor.setNeutralButton(R.string.clone_access_disconnect, null)
        val dialogo = construtor.show()
        // Substitui o clique padrão para o diálogo continuar aberto enquanto a verificação roda.
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            verificar(campo.text.toString(), status, progresso, dialogo, aoLiberar)
        }
        if (conectado) {
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                desconectar()
                dialogo.dismiss()
            }
        }
    }

    private fun verificar(
        digitado: String,
        status: TextView,
        progresso: ProgressBar,
        dialogo: AlertDialog,
        aoLiberar: () -> Unit
    ) {
        val token = digitado.trim()
        if (!ClonagemAcessoRegras.tokenPlausivel(token)) {
            status.setText(R.string.clone_access_err_token)
            return
        }
        progresso.visibility = View.VISIBLE
        status.setText(R.string.clone_access_verifying)
        activity.lifecycleScope.launch {
            val verificacao = ClonagemAcessoHf.verificar(token)
            progresso.visibility = View.GONE
            val usuario = verificacao.usuario
            if (usuario == null) {
                // Token recusado ou sem rede: o token antigo (se houver) continua salvo, mas sem acesso verificado.
                prefs.clonagemAcessoVerificado = false
                mensagemDe(verificacao.resultado)?.let { status.setText(it) }
                return@launch
            }
            if (SecurePreferences.setHfTokenClonagem(activity, token) == SecurePreferences.SaveResult.FAILED) {
                prefs.clonagemAcessoVerificado = false
                status.setText(R.string.clone_access_err_save)
                return@launch
            }
            val liberado = verificacao.resultado == ResultadoAcessoClonagem.LIBERADO
            prefs.clonagemAcessoUsuario = usuario
            prefs.clonagemAcessoVerificado = liberado
            if (liberado) {
                val nome = usuario.ifBlank { "Hugging Face" }
                Toast.makeText(activity, activity.getString(R.string.clone_access_ok, nome), Toast.LENGTH_LONG).show()
                dialogo.dismiss()
                aoLiberar()
            } else {
                mensagemDe(verificacao.resultado)?.let { status.setText(it) }
            }
        }
    }

    private fun mensagemDe(resultado: ResultadoAcessoClonagem): Int? = when (resultado) {
        ResultadoAcessoClonagem.LIBERADO -> null
        ResultadoAcessoClonagem.TOKEN_INVALIDO -> R.string.clone_access_err_token
        ResultadoAcessoClonagem.FALTA_ACEITE_KYUTAI -> R.string.clone_access_err_kyutai
        ResultadoAcessoClonagem.FALTA_ACEITE_CODIFICADORES -> R.string.clone_access_err_encoders
        ResultadoAcessoClonagem.ERRO_REDE -> R.string.clone_access_err_network
        ResultadoAcessoClonagem.ERRO_CONFIGURACAO -> R.string.clone_access_err_config
    }

    private fun desconectar() {
        SecurePreferences.setHfTokenClonagem(activity, "")
        prefs.clonagemAcessoVerificado = false
        prefs.clonagemAcessoUsuario = ""
        Toast.makeText(activity, R.string.clone_access_disconnected, Toast.LENGTH_LONG).show()
    }

    private fun nomeDaConta(): String = prefs.clonagemAcessoUsuario.ifBlank { "Hugging Face" }

    private fun abrir(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.clone_access_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun texto(res: Int) = TextView(activity).apply {
        setText(res)
        textSize = 14f
        setPadding(0, dp(10), 0, 0)
    }

    private fun botao(res: Int, acao: () -> Unit) = Button(activity).apply {
        setText(res)
        setOnClickListener { acao() }
    }
}
