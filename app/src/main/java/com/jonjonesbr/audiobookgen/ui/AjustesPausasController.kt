package com.jonjonesbr.audiobookgen.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import kotlinx.coroutines.launch

private const val PAUSA_300_MS = 300
private const val PAUSA_600_MS = 600
private const val PAUSA_900_MS = 900
private const val PAUSA_1500_MS = 1500
// Teto do slider de pausa entre frases (kokoro) — 2s já é uma pausa dramática pra uma
// frase só; casa com o android:max="2000" do SeekBar (progresso = ms direto, sem offset).
private const val PAUSA_FINAL_FRASE_MS_MAX = 2000

/**
 * Pausas nos Ajustes (rb-11): a pausa entre frases fixa (botões de 0 a 1,5 s), a pausa entre frases do kokoro
 * e a chave "Pausas naturais". Extraído da [SettingsActivity]; as views são achadas aqui, e a Activity chama
 * [carregar] (ao montar a tela e depois de restaurar) e [configurar] (uma vez, com os listeners).
 */
class AjustesPausasController(
    private val activity: AppCompatActivity,
    private val pythonEngine: PythonEngineUseCase,
) {
    private val appPrefs by lazy { AppPrefs(activity) }

    private val btnPausa0: Button = activity.findViewById(R.id.btnPausa0)
    private val btnPausa300: Button = activity.findViewById(R.id.btnPausa300)
    private val btnPausa600: Button = activity.findViewById(R.id.btnPausa600)
    private val btnPausa900: Button = activity.findViewById(R.id.btnPausa900)
    private val btnPausa1500: Button = activity.findViewById(R.id.btnPausa1500)
    private val botoesPausa get() = listOf(
        btnPausa0 to 0, btnPausa300 to PAUSA_300_MS, btnPausa600 to PAUSA_600_MS,
        btnPausa900 to PAUSA_900_MS, btnPausa1500 to PAUSA_1500_MS
    )

    // Pausa entre frases na leitura guiada com kokoro
    private val seekPausaFinalFrase: SeekBar = activity.findViewById(R.id.seekPausaFinalFrase)
    private val tvPausaFinalFraseValor: TextView = activity.findViewById(R.id.tvPausaFinalFraseValor)
    private val chkPausasNaturais: CompoundButton = activity.findViewById(R.id.chkPausasNaturais)
    private val divPausaFrase: View = activity.findViewById(R.id.divPausaFrase)
    private val blocoPausaFrase: View = activity.findViewById(R.id.blocoPausaFrase)

    /** Mostra os valores salvos (botões, slider e chave), sem gravar nada. */
    fun carregar() {
        atualizarBotoesPausa(appPrefs.pausaMs)
        val pausaFrase = appPrefs.pausaFinalFraseMs.coerceIn(0, PAUSA_FINAL_FRASE_MS_MAX)
        seekPausaFinalFrase.progress = pausaFrase
        tvPausaFinalFraseValor.text = activity.getString(R.string.settings_pausa_frase_value, pausaFrase)
        ViewCompat.setStateDescription(seekPausaFinalFrase, tvPausaFinalFraseValor.text)
        chkPausasNaturais.isChecked = appPrefs.pausasNaturais
        atualizarPausaFixaVisivel()
    }

    fun configurar() {
        for ((btn, ms) in botoesPausa) {
            btn.setOnClickListener { salvarPausa(ms) }
        }
        chkPausasNaturais.setOnCheckedChangeListener { _, marcado ->
            appPrefs.pausasNaturais = marcado
            atualizarPausaFixaVisivel()
        }
        seekPausaFinalFrase.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvPausaFinalFraseValor.text = activity.getString(R.string.settings_pausa_frase_value, progress)
                ViewCompat.setStateDescription(sb, tvPausaFinalFraseValor.text)
                appPrefs.pausaFinalFraseMs = progress
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    /** Toque na linha "Pausas naturais": liga ou desliga o switch (que grava no listener). */
    fun alternarPausasNaturais() {
        chkPausasNaturais.toggle()
    }

    private fun salvarPausa(ms: Int) {
        appPrefs.pausaMs = ms
        atualizarBotoesPausa(ms)
        activity.lifecycleScope.launch {
            pythonEngine.sincronizarConfiguracoes(
                appPrefs.motorTts,
                SecurePreferences.getGeminiKeys(activity),
                ms
            )
        }
    }

    private fun atualizarBotoesPausa(selecionado: Int) {
        for ((btn, ms) in botoesPausa) {
            val sel = ms == selecionado
            val colorPrimary = ContextCompat.getColor(activity, R.color.color_primary)
            btn.setTextColor(if (sel) colorPrimary
                else ContextCompat.getColor(activity, R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }

    /** Com as pausas naturais ligadas a pausa fixa não vale: o bloco some até elas serem desligadas. */
    private fun atualizarPausaFixaVisivel() {
        val visivel = if (appPrefs.pausasNaturais) View.GONE else View.VISIBLE
        divPausaFrase.visibility = visivel
        blocoPausaFrase.visibility = visivel
    }
}
