package com.jonjonesbr.audiobookgen.ui

import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Texto completo da GPL-3.0, embutido nos assets (cópia byte a byte do LICENSE da raiz; ver LicencaEmbutidaTest). */
private const val ARQUIVO_GPL3 = "licencas/GPL-3.0.txt"

/** Página oficial da licença, indicada quando o texto embutido não pode ser lido. */
private const val URL_GPL3 = "https://www.gnu.org/licenses/gpl-3.0.html"

/**
 * Aviso legal da GPL-3.0 (§0 e §5(d)): copyright, ausência de garantia, direito de redistribuir e onde ler a
 * licença. Os links da mensagem são clicáveis; "Ver licença" abre o texto completo com rolagem.
 */
internal fun mostrarAvisoDeLicenca(activity: AppCompatActivity, dialogos: DialogosAbertos) {
    val dialogo = AlertDialog.Builder(activity)
        .setTitle(R.string.licenca_titulo)
        .setMessage(R.string.licenca_aviso_mensagem)
        .setPositiveButton(R.string.licenca_ver_texto) { _, _ -> mostrarTextoDaLicenca(activity, dialogos) }
        .setNegativeButton(R.string.licenca_fechar, null)
        .create()
    dialogos.mostrar(dialogo)
    // Depois de exibido, o TextView da mensagem já existe: só então dá para torná-lo clicável.
    dialogo.findViewById<TextView>(android.R.id.message)?.apply {
        Linkify.addLinks(this, Linkify.WEB_URLS)
        movementMethod = LinkMovementMethod.getInstance()
    }
}

/** Lê o texto embutido fora da thread principal e o mostra num diálogo com rolagem, em fonte monoespaçada. */
private fun mostrarTextoDaLicenca(activity: AppCompatActivity, dialogos: DialogosAbertos) {
    activity.lifecycleScope.launch {
        val texto = withContext(Dispatchers.IO) {
            runCatching { activity.assets.open(ARQUIVO_GPL3).bufferedReader().use { it.readText() } }.getOrNull()
        }
        if (texto == null) {
            Toast.makeText(activity, activity.getString(R.string.licenca_erro_leitura, URL_GPL3), Toast.LENGTH_LONG)
                .show()
            return@launch
        }
        val vista = LayoutInflater.from(activity).inflate(R.layout.dialog_texto_licenca, null)
        vista.findViewById<TextView>(R.id.tvTextoLicenca).text = texto
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.licenca_texto_titulo)
            .setView(vista)
            .setPositiveButton(R.string.licenca_fechar, null)
            .create()
        dialogos.mostrar(dialogo)
    }
}
