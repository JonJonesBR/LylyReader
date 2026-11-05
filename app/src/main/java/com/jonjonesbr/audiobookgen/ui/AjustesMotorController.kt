package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.util.mostrarErro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seção "Motor" dos Ajustes (rb-11): a escolha da voz (motor TTS) e as chaves de API do Gemini e do ElevenLabs.
 * Extraído da [SettingsActivity]. A Activity chama [carregar] (ao montar), [configurar] (com os listeners) e
 * [focarChaveSePedido] (no fim do onCreate). A página do motor abre pelo callback [Callbacks.abrirPaginaDoMotor].
 */
class AjustesMotorController(
    private val activity: AppCompatActivity,
    private val pythonEngine: PythonEngineUseCase,
    private val callbacks: Callbacks,
) {
    data class Callbacks(val abrirPaginaDoMotor: () -> Unit)

    private val appPrefs by lazy { AppPrefs(activity) }

    // Motor TTS
    private val radioGroupMotor: RadioGroup = activity.findViewById(R.id.radioGroupMotor)
    private val layoutGeminiKey: LinearLayout = activity.findViewById(R.id.layoutGeminiKey)
    private val etGeminiKey: EditText = activity.findViewById(R.id.etGeminiKey)
    private val btnSalvarChave: Button = activity.findViewById(R.id.btnSalvarChave)
    private val layoutElevenLabsKey: LinearLayout = activity.findViewById(R.id.layoutElevenLabsKey)
    private val etElevenLabsKey: EditText = activity.findViewById(R.id.etElevenLabsKey)
    private val btnSalvarChaveElevenLabs: Button = activity.findViewById(R.id.btnSalvarChaveElevenLabs)
    private val scroll: NestedScrollView = activity.findViewById(R.id.scrollSettings)

    /** Mostra o motor salvo, as chaves e os tamanhos dos downloads, sem gravar nada. */
    fun carregar() {
        // Motor TTS — reflete o motor REAL salvo (antes só existiam Edge/Gemini aqui, e um
        // usuário com vozes offline/Android via "Edge" marcado, o que era mentira).
        val motor = appPrefs.motorTts
        val radioId = when (motor) {
            "gemini"     -> R.id.radioGemini
            "onnx"       -> R.id.radioOnnx
            "kokoro"     -> R.id.radioKokoro
            "pocket"     -> R.id.radioPocket
            "android"    -> R.id.radioAndroidTts
            "elevenlabs" -> R.id.radioElevenLabs
            else         -> R.id.radioEdge
        }
        activity.findViewById<android.widget.RadioButton>(radioId).isChecked = true
        layoutGeminiKey.visibility = if (motor == "gemini") View.VISIBLE else View.GONE
        etGeminiKey.setText(SecurePreferences.getGeminiKeys(activity))
        layoutElevenLabsKey.visibility = if (motor == "elevenlabs") View.VISIBLE else View.GONE
        etElevenLabsKey.setText(SecurePreferences.getElevenLabsKey(activity))
        atualizarMotorAtual(motor)
        carregarTamanhosDoMotor()
    }

    fun configurar() {
        // Motor TTS
        radioGroupMotor.setOnCheckedChangeListener { _, checkedId ->
            val motor = when (checkedId) {
                R.id.radioGemini     -> "gemini"
                R.id.radioOnnx       -> "onnx"
                R.id.radioKokoro     -> "kokoro"
                R.id.radioPocket     -> "pocket"
                R.id.radioAndroidTts -> "android"
                R.id.radioElevenLabs -> "elevenlabs"
                else                 -> "edge"
            }
            layoutGeminiKey.visibility = if (motor == "gemini") View.VISIBLE else View.GONE
            layoutElevenLabsKey.visibility = if (motor == "elevenlabs") View.VISIBLE else View.GONE
            atualizarMotorAtual(motor)
            salvarMotor(motor)
        }

        // Chave Gemini
        btnSalvarChave.setOnClickListener { salvarChaveGemini() }
        etGeminiKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) btnSalvarChave.performClick()
            false
        }

        // Chave ElevenLabs
        btnSalvarChaveElevenLabs.setOnClickListener { salvarChaveElevenLabs() }
        etElevenLabsKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) btnSalvarChaveElevenLabs.performClick()
            false
        }
    }

    /** Se aberta com extra "focar_chave_gemini"/"focar_chave_elevenlabs" (seleção de voz sem
     * chave), rola até o campo da chave e o foca — cumpre a promessa do toast de configuração. */
    fun focarChaveSePedido() {
        val campo = when {
            activity.intent.getBooleanExtra("focar_chave_gemini", false) -> layoutGeminiKey
            activity.intent.getBooleanExtra("focar_chave_elevenlabs", false) -> layoutElevenLabsKey
            else -> return
        }
        callbacks.abrirPaginaDoMotor()
        scroll.post {
            val alvo = IntArray(2)
            val conteudo = IntArray(2)
            campo.getLocationInWindow(alvo)
            scroll.getChildAt(0)?.getLocationInWindow(conteudo)
            scroll.smoothScrollTo(0, (alvo[1] - conteudo[1]).coerceAtLeast(0))
            val edit = if (campo === layoutGeminiKey) etGeminiKey else etElevenLabsKey
            edit.requestFocus()
            val teclado = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            teclado?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Valor da linha "Voz que lê os livros": o nome curto da voz escolhida. */
    private fun atualizarMotorAtual(motor: String) {
        activity.findViewById<TextView>(R.id.tvMotorAtual).text = when (motor) {
            "gemini" -> "Gemini"
            "onnx" -> "Supertonic"
            "kokoro" -> "Kokoro"
            "pocket" -> "Pocket"
            "android" -> "Android"
            "elevenlabs" -> "ElevenLabs"
            else -> "Microsoft"
        }
    }

    /**
     * Tamanho do download do Kokoro e do Pocket nas descrições do motor. Vem do mesmo catálogo que a Central
     * de downloads mostra antes de baixar (`tamanhoDownloadMb`); lido fora da thread principal, como o card de vozes.
     */
    private fun carregarTamanhosDoMotor() {
        activity.lifecycleScope.launch {
            val (kokoroMb, pocketMb) = withContext(Dispatchers.IO) {
                val pacotes = com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacotesRegistrados()
                // O Pocket baixa um pacote por idioma; o do português representa os três.
                val kokoro = pacotes.firstOrNull { it.id == "kokoro" }?.tamanhoDownloadMb
                val pocket = pacotes.firstOrNull { it.id == "pocket-ptbr-int8" }?.tamanhoDownloadMb
                kokoro to pocket
            }
            // Sem tamanho (catálogo não carregou ou pacote ausente), a linha fica oculta em vez de vazia.
            activity.findViewById<TextView>(R.id.tvMotorDescKokoro).apply {
                text = kokoroMb?.let { activity.getString(R.string.aj_motor_desc_kokoro, it) } ?: ""
                visibility = if (kokoroMb != null) View.VISIBLE else View.GONE
            }
            activity.findViewById<TextView>(R.id.tvMotorDescPocket).apply {
                text = pocketMb?.let { activity.getString(R.string.aj_motor_desc_pocket, it) } ?: ""
                visibility = if (pocketMb != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun salvarMotor(motor: String) {
        appPrefs.motorTts = motor
        activity.lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(
                motor,
                SecurePreferences.getGeminiKeys(activity),
                appPrefs.pausaMs
            )
        }
    }

    private fun salvarGeminiKey(chave: String): SecurePreferences.SaveResult {
        val resultado = SecurePreferences.setGeminiKeys(activity, chave)
        if (resultado == SecurePreferences.SaveResult.FAILED) return resultado
        activity.lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(appPrefs.motorTts, chave, appPrefs.pausaMs)
        }
        return resultado
    }

    // ElevenLabs não passa pelo Python (chamada HTTP direta em Kotlin), então não precisa
    // de sincronizarConfiguracoes — a chave é lida direto de SecurePreferences na hora da síntese.
    private fun salvarElevenLabsKey(chave: String): SecurePreferences.SaveResult =
        SecurePreferences.setElevenLabsKey(activity, chave)

    /** Extraído de [configurar] pra manter dentro do limite de linhas do detekt. */
    private fun salvarChaveGemini() {
        val chave = etGeminiKey.text.toString().trim()
        when (salvarGeminiKey(chave)) {
            SecurePreferences.SaveResult.SECURE -> Toast.makeText(
                activity,
                if (chave.isNotBlank()) R.string.toast_key_saved else R.string.toast_key_removed,
                Toast.LENGTH_SHORT
            ).show()
            SecurePreferences.SaveResult.LOCAL_FALLBACK -> Toast.makeText(
                activity,
                if (chave.isNotBlank()) R.string.toast_key_saved_local_fallback else R.string.toast_key_removed,
                Toast.LENGTH_LONG
            ).show()
            SecurePreferences.SaveResult.FAILED -> activity.mostrarErro(R.string.toast_key_secure_save_failed)
        }
    }

    /** Extraído de [configurar] pra manter dentro do limite de linhas do detekt. */
    private fun salvarChaveElevenLabs() {
        val chave = etElevenLabsKey.text.toString().trim()
        when (salvarElevenLabsKey(chave)) {
            SecurePreferences.SaveResult.SECURE -> Toast.makeText(
                activity,
                if (chave.isNotBlank()) R.string.toast_key_saved else R.string.toast_key_removed,
                Toast.LENGTH_SHORT
            ).show()
            SecurePreferences.SaveResult.LOCAL_FALLBACK -> Toast.makeText(
                activity,
                if (chave.isNotBlank()) R.string.toast_key_saved_local_fallback else R.string.toast_key_removed,
                Toast.LENGTH_LONG
            ).show()
            SecurePreferences.SaveResult.FAILED -> activity.mostrarErro(R.string.toast_key_secure_save_failed)
        }
    }
}
