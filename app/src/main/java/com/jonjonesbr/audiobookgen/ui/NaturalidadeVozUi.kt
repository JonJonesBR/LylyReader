package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.ChipGroup
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import java.util.Locale

/**
 * Ajustes › Som › "Naturalidade da voz (experimental)" (quatro interruptores) e, em Ajustes avançados, a
 * expressividade do Pocket TTS: deslizante de mínimo a máximo, com atalhos (Padrão, Moderada, Expressiva, Máxima).
 */
class NaturalidadeVozUi(private val activity: AppCompatActivity) {

    private val prefs = AppPrefs(activity)
    private val swEntonacao: CompoundButton = activity.findViewById(R.id.swEntonacaoContinua)
    private val swAcabamento: CompoundButton = activity.findViewById(R.id.swAcabamentoAudio)
    private val swDialogos: CompoundButton = activity.findViewById(R.id.swDialogosVivos)
    private val swFolga: CompoundButton = activity.findViewById(R.id.swFolgaFrasesCurtas)
    private val seekExpr: SeekBar = activity.findViewById(R.id.seekPocketExpressividade)
    private val tvExpr: TextView = activity.findViewById(R.id.tvPocketExpressividadeValor)
    private val chipsExpr: ChipGroup = activity.findViewById(R.id.chipsPocketExpressividade)
    private var carregando = false

    fun vincular() {
        activity.findViewById<android.view.View>(R.id.rowEntonacaoContinua).setOnClickListener { swEntonacao.toggle() }
        activity.findViewById<android.view.View>(R.id.rowAcabamentoAudio).setOnClickListener { swAcabamento.toggle() }
        activity.findViewById<android.view.View>(R.id.rowDialogosVivos).setOnClickListener { swDialogos.toggle() }
        activity.findViewById<android.view.View>(R.id.rowFolgaFrasesCurtas).setOnClickListener { swFolga.toggle() }
        swEntonacao.setOnCheckedChangeListener { _, v -> prefs.entonacaoContinua = v }
        swAcabamento.setOnCheckedChangeListener { _, v -> prefs.acabamentoAudio = v }
        swDialogos.setOnCheckedChangeListener { _, v -> prefs.dialogosVivos = v }
        swFolga.setOnCheckedChangeListener { _, v -> prefs.folgaFrasesCurtas = v }

        seekExpr.max = AppPrefs.POCKET_EXPRESSIVIDADE_MAX - AppPrefs.POCKET_EXPRESSIVIDADE_MIN
        seekExpr.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                mostrarExpressividade(progress + AppPrefs.POCKET_EXPRESSIVIDADE_MIN)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.pocketExpressividade = seekExpr.progress + AppPrefs.POCKET_EXPRESSIVIDADE_MIN
            }
        })
        chipsExpr.setOnCheckedStateChangeListener { _, ids ->
            val valor = PRESETS.entries.firstOrNull { it.key == ids.firstOrNull() }?.value
            if (!carregando && valor != null) {
                prefs.pocketExpressividade = valor
                seekExpr.progress = valor - AppPrefs.POCKET_EXPRESSIVIDADE_MIN
            }
        }
        carregar()
    }

    /** Mostra os valores salvos (também depois de "Restaurar padrões de som"). */
    fun carregar() {
        carregando = true
        swEntonacao.isChecked = prefs.entonacaoContinua
        swAcabamento.isChecked = prefs.acabamentoAudio
        swDialogos.isChecked = prefs.dialogosVivos
        swFolga.isChecked = prefs.folgaFrasesCurtas
        seekExpr.progress = prefs.pocketExpressividade - AppPrefs.POCKET_EXPRESSIVIDADE_MIN
        mostrarExpressividade(prefs.pocketExpressividade)
        carregando = false
    }

    private fun mostrarExpressividade(decimos: Int) {
        tvExpr.text = rotuloExpressividade(activity, decimos)
        val chip = PRESETS.entries.firstOrNull { it.value == decimos }?.key
        val antes = carregando
        carregando = true
        if (chip != null) chipsExpr.check(chip) else chipsExpr.clearCheck()
        carregando = antes
    }

    companion object {
        /** 0,3: o padrão anterior do app. */
        private const val POCKET_MODERADA = 3
        /** 0,7: o padrão dos autores do Pocket TTS (Kyutai). */
        private const val POCKET_EXPRESSIVA = 7
        private const val DECIMOS = 10f

        /** Atalhos: chip → expressividade em décimos. */
        private val PRESETS = linkedMapOf(
            R.id.chipExprPadrao to AppPrefs.POCKET_EXPRESSIVIDADE_PADRAO,
            R.id.chipExprModerada to POCKET_MODERADA,
            R.id.chipExprExpressiva to POCKET_EXPRESSIVA,
            R.id.chipExprMaxima to AppPrefs.POCKET_EXPRESSIVIDADE_MAX
        )

        /** "0,1 · Padrão", "0,5", "0,7 · Expressiva"… (o nome só aparece nos atalhos). */
        fun rotuloExpressividade(context: Context, decimos: Int): String {
            val numero = String.format(Locale.getDefault(), "%.1f", decimos / DECIMOS)
            val nome = when (decimos) {
                AppPrefs.POCKET_EXPRESSIVIDADE_PADRAO -> R.string.pocket_expr_padrao
                POCKET_MODERADA -> R.string.pocket_expr_moderada
                POCKET_EXPRESSIVA -> R.string.pocket_expr_expressiva
                AppPrefs.POCKET_EXPRESSIVIDADE_MAX -> R.string.pocket_expr_maxima
                else -> null
            }
            return nome?.let { context.getString(R.string.pocket_expr_valor, numero, context.getString(it)) } ?: numero
        }
    }
}
