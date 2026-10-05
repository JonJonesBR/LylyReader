package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.util.PRESETS_SONECA_MIN
import android.app.Activity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * Diálogo de seleção do sleep timer, compartilhado entre o mini-player ([BasePlayerActivity])
 * e a leitura guiada ([ReaderActivity]). Presets de 1 toque (5/10/20/30/60/90/120 min) + slider
 * de 5 em 5 minutos pra qualquer valor entre eles — pedido do usuário, que achava os presets
 * antigos (15/30/45/60) pouco flexíveis.
 */
object SleepTimerDialog {

    private const val MS_POR_MINUTO = 60_000L

    /** [sessionToken]: token da sessão de leitura guiada vigente (GuidedPlayerManager.sessionToken),
     *  ou null quando a soneca não é para uma sessão de leitura guiada (ex.: mini-player de
     *  áudio convertido). Repassado a SleepTimerManager.start() para travar a soneca à sessão
     *  certa — ver SleepTimerManager.armedToken. */
    fun show(activity: Activity, sessionToken: Long? = null) {
        val stm = SleepTimerManager
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_sleep_timer, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)

        val ativo = stm.remainingMs.value
        view.findViewById<TextView>(R.id.tvSonecaTitulo).text = if (ativo != null) {
            activity.getString(R.string.sleep_timer_title_active, stm.formatRemaining(ativo))
        } else {
            activity.getString(R.string.sleep_timer_label)
        }

        fun aplicar(min: Int) {
            if (min <= 0) {
                stm.cancel()
                Toast.makeText(activity, R.string.toast_sleep_timer_off, Toast.LENGTH_SHORT).show()
            } else {
                stm.start(min * MS_POR_MINUTO, sessionToken)
                Toast.makeText(
                    activity, activity.getString(R.string.toast_sleep_timer_set, min), Toast.LENGTH_SHORT
                ).show()
            }
            dialog.dismiss()
        }

        val idsPresets = listOf(
            R.id.btnSoneca5, R.id.btnSoneca10, R.id.btnSoneca20, R.id.btnSoneca30,
            R.id.btnSoneca60, R.id.btnSoneca90, R.id.btnSoneca120
        )
        idsPresets.zip(PRESETS_SONECA_MIN.toList()) { id, min ->
            view.findViewById<Button>(id).setOnClickListener { aplicar(min) }
        }
        view.findViewById<Button>(R.id.btnSonecaOff).setOnClickListener { aplicar(0) }

        val tvPersonalizada = view.findViewById<TextView>(R.id.tvSonecaPersonalizada)
        tvPersonalizada.text = activity.getString(R.string.sleep_timer_custom_off_label)
        val slider = view.findViewById<Slider>(R.id.sliderSoneca)
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val min = value.toInt()
            tvPersonalizada.text = if (min <= 0) {
                activity.getString(R.string.sleep_timer_custom_off_label)
            } else {
                activity.getString(R.string.sleep_timer_custom_label, min)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            @Suppress("EmptyFunctionBlock")
            override fun onStartTrackingTouch(slider: Slider) {
                // Nada a fazer — só o valor final (onStopTrackingTouch) importa aqui.
            }
            override fun onStopTrackingTouch(slider: Slider) { aplicar(slider.value.toInt()) }
        })

        dialog.show()
    }
}
