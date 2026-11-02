package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.util.PRESETS_SONECA_MIN
import com.jonjonesbr.audiobookgen.util.expandirEmPaisagem
import android.app.Activity
import android.view.View
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
 *
 * O botão "Fim do capítulo" só aparece com [sessionToken] (ou seja, na leitura guiada): a soneca de capítulo
 * depende do gatilho de fim de parágrafo do GuidedPlayerManager, que o audiobook não tem.
 */
object SleepTimerDialog {

    private const val MS_POR_MINUTO = 60_000L
    private const val MINUTOS_POR_HORA = 60

    /** Texto de um preset: "5 min", "1 h", "1 h 30" (o número vai no texto, o TalkBack lê a unidade junto). */
    private fun rotuloPreset(activity: Activity, min: Int): String = when {
        min < MINUTOS_POR_HORA -> activity.getString(R.string.sleep_timer_chip_minutos, min)
        min % MINUTOS_POR_HORA == 0 -> activity.getString(R.string.sleep_timer_chip_horas, min / MINUTOS_POR_HORA)
        else -> activity.getString(
            R.string.sleep_timer_chip_horas_minutos, min / MINUTOS_POR_HORA, min % MINUTOS_POR_HORA
        )
    }

    /** Descrição falada de um preset ("5 minutos", "1 hora", "1 hora e 30 minutos"); o TalkBack lê mal "1 h 30". */
    private fun descricaoPreset(activity: Activity, min: Int): String {
        val res = activity.resources
        return when {
            min < MINUTOS_POR_HORA -> res.getQuantityString(R.plurals.tempo_minutos, min, min)
            min % MINUTOS_POR_HORA == 0 -> {
                val horas = min / MINUTOS_POR_HORA
                res.getQuantityString(R.plurals.tempo_horas, horas, horas)
            }
            else -> {
                val horas = min / MINUTOS_POR_HORA
                val minutos = min % MINUTOS_POR_HORA
                activity.getString(
                    R.string.sleep_timer_cd_horas_minutos,
                    res.getQuantityString(R.plurals.tempo_horas, horas, horas),
                    res.getQuantityString(R.plurals.tempo_minutos, minutos, minutos)
                )
            }
        }
    }

    /** [sessionToken]: token da sessão de leitura guiada vigente (GuidedPlayerManager.sessionToken),
     *  ou null quando a soneca não é para uma sessão de leitura guiada (ex.: mini-player de
     *  áudio convertido). Repassado a SleepTimerManager.start() para travar a soneca à sessão
     *  certa — ver SleepTimerManager.armedToken. */
    fun show(activity: Activity, sessionToken: Long? = null) {
        val stm = SleepTimerManager
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_sleep_timer, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)
        dialog.expandirEmPaisagem()

        val ativo = stm.remainingMs.value
        view.findViewById<TextView>(R.id.tvSonecaTitulo).text = when {
            stm.fimDoCapitulo.value -> activity.getString(R.string.sleep_timer_title_fim_capitulo)
            ativo != null -> activity.getString(R.string.sleep_timer_title_active, stm.formatRemaining(ativo))
            else -> activity.getString(R.string.sleep_timer_label)
        }

        val btnFimDoCapitulo = view.findViewById<Button>(R.id.btnSonecaFimCapitulo)
        if (sessionToken != null) {
            btnFimDoCapitulo.visibility = View.VISIBLE
            btnFimDoCapitulo.setOnClickListener {
                stm.armarFimDoCapitulo(sessionToken)
                Toast.makeText(activity, R.string.toast_sleep_timer_fim_capitulo, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        // fechar = false: ajuste passo a passo (TalkBack, teclado), que vale já mas mantém o diálogo aberto.
        fun aplicar(min: Int, fechar: Boolean = true) {
            if (min <= 0) {
                stm.cancel()
                if (fechar) Toast.makeText(activity, R.string.toast_sleep_timer_off, Toast.LENGTH_SHORT).show()
            } else {
                stm.start(min * MS_POR_MINUTO, sessionToken)
                if (fechar) {
                    Toast.makeText(
                        activity, activity.getString(R.string.toast_sleep_timer_set, min), Toast.LENGTH_SHORT
                    ).show()
                }
            }
            if (fechar) dialog.dismiss()
        }

        val idsPresets = listOf(
            R.id.btnSoneca5, R.id.btnSoneca10, R.id.btnSoneca20, R.id.btnSoneca30,
            R.id.btnSoneca60, R.id.btnSoneca90, R.id.btnSoneca120
        )
        idsPresets.zip(PRESETS_SONECA_MIN.toList()) { id, min ->
            view.findViewById<Button>(id).apply {
                text = rotuloPreset(activity, min)
                // O texto curto ("1 h 30") não é lido por extenso pelo TalkBack: a descrição falada vai aqui.
                contentDescription = descricaoPreset(activity, min)
                setOnClickListener { aplicar(min) }
            }
        }
        view.findViewById<Button>(R.id.btnSonecaOff).setOnClickListener { aplicar(0) }

        val tvPersonalizada = view.findViewById<TextView>(R.id.tvSonecaPersonalizada)
        tvPersonalizada.text = activity.getString(R.string.sleep_timer_custom_off_label)
        val slider = view.findViewById<Slider>(R.id.sliderSoneca)
        var arrastando = false
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val min = value.toInt()
            tvPersonalizada.text = if (min <= 0) {
                activity.getString(R.string.sleep_timer_custom_off_label)
            } else {
                activity.getString(R.string.sleep_timer_custom_label, min)
            }
            // TalkBack e teclado mudam o valor sem arraste: liga a soneca já, sem fechar o diálogo.
            if (!arrastando) aplicar(min, fechar = false)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { arrastando = true }
            override fun onStopTrackingTouch(slider: Slider) {
                arrastando = false
                aplicar(slider.value.toInt())
            }
        })

        dialog.show()
    }
}
