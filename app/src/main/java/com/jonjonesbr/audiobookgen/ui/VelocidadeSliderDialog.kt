package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.VELOCIDADES_GUIADAS
import com.jonjonesbr.audiobookgen.util.aplicarSnapVelocidade
import android.app.Activity
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

private const val EPSILON_VELOCIDADE = 0.01f

/**
 * Slider de velocidade com presets (0.75x-2.0x) + valor personalizado, "travando" perto de um
 * preset ao arrastar — usado tanto na leitura guiada (barra flutuante) quanto na configuração
 * prévia do livro ([ConfigLivroDialog]), substituindo o botão que só ciclava entre presets.
 */
object VelocidadeSliderDialog {

    fun show(activity: Activity, valorAtual: Float, aoAplicar: (Float) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_velocidade, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)

        fun aplicar(valor: Float) {
            aoAplicar(valor)
            dialog.dismiss()
        }

        val idsPresets = listOf(
            R.id.btnVelocidade075, R.id.btnVelocidade100, R.id.btnVelocidade125,
            R.id.btnVelocidade150, R.id.btnVelocidade175, R.id.btnVelocidade200
        )
        idsPresets.zip(VELOCIDADES_GUIADAS.toList()) { id, valor ->
            view.findViewById<Button>(id).setOnClickListener { aplicar(valor) }
        }

        val tvPersonalizada = view.findViewById<TextView>(R.id.tvVelocidadePersonalizada)
        // Mesmo padrão de GuidedReadingBarController.atualizarRotuloVelocidadeGuided(): sem
        // String.format (evita vírgula decimal em locales pt-BR — "1,15×" ficaria estranho
        // com o símbolo ×), Float.toString() já é limpo pros valores em passos de 0.05.
        fun formatar(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0×" else "${v}×"
        tvPersonalizada.text = formatar(valorAtual)

        val slider = view.findViewById<Slider>(R.id.sliderVelocidade)
        slider.value = valorAtual.coerceIn(slider.valueFrom, slider.valueTo)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) tvPersonalizada.text = formatar(value)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            @Suppress("EmptyFunctionBlock")
            override fun onStartTrackingTouch(slider: Slider) {
                // Nada a fazer — só o valor final (onStopTrackingTouch) importa aqui.
            }
            override fun onStopTrackingTouch(slider: Slider) {
                val travado = aplicarSnapVelocidade(slider.value)
                if (kotlin.math.abs(travado - slider.value) > EPSILON_VELOCIDADE) slider.value = travado
                aplicar(travado)
            }
        })

        dialog.show()
    }
}
