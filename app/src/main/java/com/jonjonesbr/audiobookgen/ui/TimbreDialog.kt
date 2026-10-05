package com.jonjonesbr.audiobookgen.ui

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import java.util.Locale

/**
 * Ajuste de timbre das vozes: tom (−3…+3 semitons, passos de meio semitom) e suavização de agudos
 * (0…8 dB). As mudanças valem na hora (inclusive na leitura guiada em andamento) e ficam salvas.
 */
object TimbreDialog {

    /** Controles de timbre prontos para embutir em outra tela; [restaurar] volta ao padrão e já aplica. */
    class Controles(val view: LinearLayout, val restaurar: () -> Unit)

    /**
     * [inicial] (tom, agudos) e [gravar] permitem guardar o timbre em outro lugar que não as preferências
     * globais (ex.: o perfil de um livro); sem eles, vale o comportamento de sempre.
     */
    fun controles(
        activity: AppCompatActivity,
        appPrefs: AppPrefs,
        aoMudar: () -> Unit = {},
        inicial: Pair<Int, Int>? = null,
        gravar: ((Int, Int) -> Unit)? = null
    ): Controles {
        val dp = activity.resources.displayMetrics.density
        val minimo = AppPrefs.TIMBRE_TOM_MIN

        val rotuloTom = TextView(activity).apply { textSize = 15f; setPadding(0, (12 * dp).toInt(), 0, 0) }
        val sliderTom = SeekBar(activity).apply {
            max = AppPrefs.TIMBRE_TOM_MAX - minimo
            progress = (inicial?.first ?: appPrefs.tomVozMeiosTons) - minimo
        }
        val rotuloAgudos = TextView(activity).apply { textSize = 15f; setPadding(0, (16 * dp).toInt(), 0, 0) }
        val sliderAgudos = SeekBar(activity).apply {
            max = AppPrefs.TIMBRE_AGUDOS_MAX
            progress = inicial?.second ?: appPrefs.suavizarAgudosDb
        }

        fun rotular() {
            val meios = sliderTom.progress + minimo
            val tom = if (meios == 0) {
                activity.getString(R.string.timbre_pitch_default)
            } else {
                val texto = String.format(Locale.getDefault(), "%+.1f", meios / 2f)
                activity.getString(R.string.timbre_pitch_value, texto)
            }
            rotuloTom.text = activity.getString(R.string.timbre_pitch_label, tom)
            val agudos = if (sliderAgudos.progress == 0) {
                activity.getString(R.string.timbre_treble_off)
            } else {
                activity.getString(R.string.timbre_treble_value, sliderAgudos.progress)
            }
            rotuloAgudos.text = activity.getString(R.string.timbre_treble_label, agudos)
        }

        fun salvarEAplicar() {
            if (gravar != null) {
                gravar(sliderTom.progress + minimo, sliderAgudos.progress)
            } else {
                appPrefs.tomVozMeiosTons = sliderTom.progress + minimo
                appPrefs.suavizarAgudosDb = sliderAgudos.progress
                GuidedPlaybackBridge.manager?.aplicarTimbre()
            }
            aoMudar()
        }

        val ouvinte = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = rotular()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = salvarEAplicar()
        }
        sliderTom.setOnSeekBarChangeListener(ouvinte)
        sliderAgudos.setOnSeekBarChangeListener(ouvinte)
        rotular()

        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
            addView(TextView(activity).apply { setText(R.string.timbre_desc); textSize = 12f })
            addView(rotuloTom)
            addView(sliderTom)
            addView(rotuloAgudos)
            addView(sliderAgudos)
        }
        return Controles(conteudo) {
            sliderTom.progress = -minimo
            sliderAgudos.progress = 0
            rotular()
            salvarEAplicar()
        }
    }

    fun show(activity: AppCompatActivity, appPrefs: AppPrefs, aoMudar: () -> Unit = {}) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val controles = controles(activity, appPrefs, aoMudar)
        controles.view.setPadding(pad, controles.view.paddingTop, pad, 0)
        val conteudo = controles.view
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.timbre_title)
            .setView(conteudo)
            .setPositiveButton(R.string.timbre_close, null)
            .setNeutralButton(R.string.timbre_reset, null)
            .create()
        dialogo.show()
        // "Restaurar padrão" não fecha o diálogo: zera os dois controles e já aplica.
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            controles.restaurar()
        }
    }
}
