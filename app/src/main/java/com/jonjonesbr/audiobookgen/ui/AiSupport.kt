package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.AiProvider
import com.jonjonesbr.audiobookgen.domain.AiTextClient
import com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient
import com.jonjonesbr.audiobookgen.domain.OpenAiStyleClient

/** IA escolhida pelo usuário para as análises (personagens, pronúncias): cria o cliente e deixa trocar. */
object AiSupport {

    fun provider(context: Context): AiProvider = AiProvider.fromId(AppPrefs(context).iaProvedor)

    /** Cliente pronto para uso, ou null quando falta configuração (chave Gemini, endereço/modelo do serviço próprio). */
    fun client(context: Context): AiTextClient? {
        val prefs = AppPrefs(context)
        return when (provider(context)) {
            AiProvider.GEMINI -> {
                val keys = GeminiSpeakerClient.parseKeys(SecurePreferences.getGeminiKeys(context))
                if (keys.isEmpty()) null else GeminiSpeakerClient(keys, prefs.geminiModeloAnalise)
            }
            AiProvider.POLLINATIONS -> OpenAiStyleClient(
                OpenAiStyleClient.POLLINATIONS_ENDPOINT, OpenAiStyleClient.POLLINATIONS_MODEL, rotulo = "Pollinations"
            )
            AiProvider.CUSTOM -> {
                val url = prefs.iaPersonalizadaUrl
                val model = prefs.iaPersonalizadaModelo
                if (url.isBlank() || model.isBlank()) null else OpenAiStyleClient(
                    endpoint = OpenAiStyleClient.endpointFromBase(url),
                    model = model,
                    apiKey = SecurePreferences.getIaPersonalizadaChave(context),
                    rotulo = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                )
            }
        }
    }

    fun label(context: Context): String = when (provider(context)) {
        AiProvider.GEMINI -> context.getString(R.string.ia_provider_gemini_short)
        AiProvider.POLLINATIONS -> context.getString(R.string.ia_provider_pollinations_short)
        AiProvider.CUSTOM -> AppPrefs(context).iaPersonalizadaModelo.ifBlank { context.getString(R.string.ia_provider_custom_short) }
    }

    /** Diz ao usuário o que falta configurar para a IA escolhida. */
    fun explainMissing(activity: AppCompatActivity) {
        when (provider(activity)) {
            AiProvider.GEMINI -> {
                android.widget.Toast.makeText(activity, R.string.character_ai_need_key, android.widget.Toast.LENGTH_LONG).show()
                activity.startActivity(
                    android.content.Intent(activity, SettingsActivity::class.java).putExtra("focar_chave_gemini", true)
                )
            }
            else -> {
                android.widget.Toast.makeText(activity, R.string.ia_need_custom_config, android.widget.Toast.LENGTH_LONG).show()
                chooseProvider(activity) {}
            }
        }
    }

    /** Escolha da IA: Gemini, Pollinations (grátis, sem chave) ou um serviço compatível com a OpenAI. */
    fun chooseProvider(activity: AppCompatActivity, onDone: () -> Unit) {
        val atual = provider(activity)
        val opcoes = arrayOf(
            activity.getString(R.string.ia_provider_gemini),
            activity.getString(R.string.ia_provider_pollinations),
            activity.getString(R.string.ia_provider_custom)
        )
        var escolhido = atual.ordinal
        AlertDialog.Builder(activity)
            .setTitle(R.string.ia_provider_title)
            .setSingleChoiceItems(opcoes, escolhido) { _, which -> escolhido = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val novo = AiProvider.entries[escolhido]
                AppPrefs(activity).iaProvedor = novo.id
                if (novo == AiProvider.CUSTOM) configureCustom(activity, onDone) else onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun configureCustom(activity: AppCompatActivity, onDone: () -> Unit) {
        val prefs = AppPrefs(activity)
        fun campo(dica: String, valor: String, tipo: Int = InputType.TYPE_CLASS_TEXT) = EditText(activity).apply {
            hint = dica
            setText(valor)
            inputType = tipo
            setSingleLine()
        }
        val url = campo(activity.getString(R.string.ia_custom_url_hint), prefs.iaPersonalizadaUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val modelo = campo(activity.getString(R.string.ia_custom_model_hint), prefs.iaPersonalizadaModelo)
        val chave = campo(
            activity.getString(R.string.ia_custom_key_hint), SecurePreferences.getIaPersonalizadaChave(activity),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val nota = TextView(activity).apply {
            text = activity.getString(R.string.ia_custom_note)
            textSize = 12f
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(url); addView(modelo); addView(chave); addView(nota)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.ia_custom_title)
            .setView(conteudo)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.iaPersonalizadaUrl = url.text.toString()
                prefs.iaPersonalizadaModelo = modelo.text.toString()
                SecurePreferences.setIaPersonalizadaChave(activity, chave.text.toString().trim())
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
