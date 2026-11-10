package com.jonjonesbr.audiobookgen.ui

import android.content.res.ColorStateList
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

/**
 * Pausa entre blocos nos Ajustes › Avançado (botões de 0 a 1,5 s). É da conversão de audiobook (KEY_PAUSA_MS
 * no ConversionWorker), não do ritmo da leitura guiada, que tem o seletor "Ritmo da narração". Extraído da
 * [SettingsActivity]; as views são achadas aqui, e a Activity chama [carregar] e [configurar].
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

    /** Mostra a pausa entre blocos salva (sem gravar nada). */
    fun carregar() {
        atualizarBotoesPausa(appPrefs.pausaMs)
    }

    fun configurar() {
        for ((btn, ms) in botoesPausa) {
            btn.setOnClickListener { salvarPausa(ms) }
        }
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
}
