package com.jonjonesbr.audiobookgen.ui

import android.text.InputType
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs

private const val STEPS_MIN = 2
// O mesmo teto do motor (OnnxTtsEngine limita a AppPrefs.SUPERTONIC_STEPS_MAX).
private const val STEPS_MAX = AppPrefs.SUPERTONIC_STEPS_MAX
private const val DIALOG_PADDING_H = 48
private const val DIALOG_PADDING_V = 24

/**
 * Passos de qualidade nos Ajustes (rb-11): Supertonic (qualidade x velocidade), Pocket (experimental) e
 * "Pocket mais rápido". Extraído da [SettingsActivity]; as views são achadas aqui, e a Activity chama
 * [carregar] (ao montar a tela e depois de restaurar) e [configurar] (uma vez, com os listeners).
 */
class AjustesQualidadeController(private val activity: AppCompatActivity) {
    private val appPrefs by lazy { AppPrefs(activity) }
    private val dialogos = DialogosAbertos(activity)

    // Passos do Supertonic (qualidade x velocidade)
    private val seekSupertonicSteps: SeekBar = activity.findViewById(R.id.seekSupertonicSteps)
    private val tvSupertonicStepsValor: TextView = activity.findViewById(R.id.tvSupertonicStepsValor)
    // Passos do Pocket TTS (experimental)
    private val seekPocketPassos: SeekBar = activity.findViewById(R.id.seekPocketPassos)
    private val tvPocketPassosValor: TextView = activity.findViewById(R.id.tvPocketPassosValor)
    private val swPocketMaisRapido: CompoundButton = activity.findViewById(R.id.swPocketMaisRapido)

    fun carregar() {
        val steps = appPrefs.supertonicSteps.coerceIn(STEPS_MIN, STEPS_MAX)
        seekSupertonicSteps.progress = steps - STEPS_MIN
        tvSupertonicStepsValor.text = activity.resources.getQuantityString(
            R.plurals.settings_steps_value_qtd, steps, steps
        )
        ViewCompat.setStateDescription(seekSupertonicSteps, tvSupertonicStepsValor.text)

        val passosPocket = appPrefs.pocketPassos
        seekPocketPassos.progress = passosPocket - AppPrefs.POCKET_PASSOS_MIN
        tvPocketPassosValor.text = activity.resources.getQuantityString(
            R.plurals.settings_steps_value_qtd, passosPocket, passosPocket
        )
        ViewCompat.setStateDescription(seekPocketPassos, tvPocketPassosValor.text)
        swPocketMaisRapido.isChecked = appPrefs.pocketMaisRapido
    }

    fun configurar() {
        configurarSupertonicSeekbar()
        configurarPocketPassosSeekbar()
        configurarPocketMaisRapido()
    }

    /** Toque na linha "Pocket mais rápido": liga ou desliga o switch (que grava no listener). */
    fun alternarPocketMaisRapido() {
        swPocketMaisRapido.toggle()
    }

    private fun configurarPocketPassosSeekbar() {
        seekPocketPassos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val passos = progress + AppPrefs.POCKET_PASSOS_MIN
                tvPocketPassosValor.text = activity.resources.getQuantityString(
                    R.plurals.settings_steps_value_qtd, passos, passos
                )
                ViewCompat.setStateDescription(sb, tvPocketPassosValor.text)
                if (fromUser) appPrefs.pocketPassos = passos
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun configurarPocketMaisRapido() {
        swPocketMaisRapido.setOnCheckedChangeListener { _, marcado -> appPrefs.pocketMaisRapido = marcado }
    }

    private fun configurarSupertonicSeekbar() {
        seekSupertonicSteps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val steps = progress + STEPS_MIN
                tvSupertonicStepsValor.text = activity.resources.getQuantityString(
                    R.plurals.settings_steps_value_qtd, steps, steps
                )
                ViewCompat.setStateDescription(sb, tvSupertonicStepsValor.text)
                appPrefs.supertonicSteps = steps
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        tvSupertonicStepsValor.setOnClickListener {
            val current = seekSupertonicSteps.progress + STEPS_MIN
            val input = EditText(activity).apply {
                setText(current.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                selectAll()
                setPadding(DIALOG_PADDING_H, DIALOG_PADDING_V, DIALOG_PADDING_H, DIALOG_PADDING_V)
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.settings_supertonic_steps_title)
                .setMessage(activity.getString(R.string.dialog_valor_entre, STEPS_MIN, STEPS_MAX))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val texto = input.text.toString().trim()
                    val valor = texto.toIntOrNull()
                    if (valor != null && valor in STEPS_MIN..STEPS_MAX) {
                        seekSupertonicSteps.progress = valor - STEPS_MIN
                        tvSupertonicStepsValor.text = activity.resources.getQuantityString(
                            R.plurals.settings_steps_value_qtd, valor, valor
                        )
                        appPrefs.supertonicSteps = valor
                    } else {
                        Toast.makeText(
                            activity, activity.getString(R.string.toast_valor_invalido, STEPS_MIN, STEPS_MAX),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .let { dialogos.mostrar(it) }
        }
    }
}
