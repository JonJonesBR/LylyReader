package com.jonjonesbr.audiobookgen.ui

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R

/** Escolha rápida do número de passos de síntese (Supertonic/Pocket) direto da leitura guiada. */
object PassosDialog {

    fun show(
        activity: AppCompatActivity,
        @StringRes titulo: Int,
        @StringRes descricao: Int,
        minimo: Int,
        maximo: Int,
        atual: Int,
        aoAplicar: (Int) -> Unit
    ) {
        val dp = activity.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val valor = TextView(activity).apply {
            textSize = 16f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val slider = SeekBar(activity).apply {
            max = maximo - minimo
            progress = atual.coerceIn(minimo, maximo) - minimo
        }
        fun rotular() { valor.text = activity.getString(R.string.settings_steps_value, slider.progress + minimo) }
        rotular()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = rotular()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(TextView(activity).apply { setText(descricao); textSize = 12f })
            addView(valor)
            addView(slider)
        }
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(conteudo)
            .setPositiveButton(R.string.guided_steps_apply) { _, _ -> aoAplicar(slider.progress + minimo) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
