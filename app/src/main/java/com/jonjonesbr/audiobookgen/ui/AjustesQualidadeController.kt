package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.chip.ChipGroup
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.QualidadeDaVoz
import com.jonjonesbr.audiobookgen.util.VoiceCatalog

/**
 * "Qualidade da voz" nos Ajustes › Som: Rápida, Equilibrada (padrão) ou Alta. Grava, por trás, os passos do
 * Supertonic e do Pocket ao tocar num chip. Em Avançado fica o "Pocket mais rápido" (experimental). A Activity
 * chama [carregar] e [configurar] (ver SettingsActivity).
 */
class AjustesQualidadeController(private val activity: AppCompatActivity) {
    private val appPrefs by lazy { AppPrefs(activity) }
    private val chips: ChipGroup = activity.findViewById(R.id.chipsQualidade)
    private val tvAviso: TextView = activity.findViewById(R.id.tvQualidadeAviso)
    private val swPocketMaisRapido: CompoundButton = activity.findViewById(R.id.swPocketMaisRapido)
    private val porChip = linkedMapOf(
        R.id.chipQualRapida to QualidadeDaVoz.RAPIDA,
        R.id.chipQualEquilibrada to QualidadeDaVoz.EQUILIBRADA,
        R.id.chipQualAlta to QualidadeDaVoz.ALTA
    )
    // Evita regravar o que acabou de ser lido (ou o eco de check() dentro da própria tela).
    private var carregando = false

    /** Mostra o preset mais próximo do valor salvo (sem gravar nada). */
    fun carregar() {
        val motor = VoiceCatalog.effectiveEngine(appPrefs.vozSelecionada.orEmpty(), appPrefs.motorTts)
        mostrar(QualidadeDaVoz.exibida(motor, appPrefs.supertonicSteps, appPrefs.pocketPassos))
        swPocketMaisRapido.isChecked = appPrefs.pocketMaisRapido
    }

    fun configurar() {
        chips.setOnCheckedStateChangeListener { _, ids ->
            if (carregando) return@setOnCheckedStateChangeListener
            val qualidade = ids.firstOrNull()?.let { porChip[it] } ?: return@setOnCheckedStateChangeListener
            appPrefs.supertonicSteps = qualidade.supertonicPassos
            appPrefs.pocketPassos = qualidade.pocketPassos
            mostrar(qualidade)
        }
        swPocketMaisRapido.setOnCheckedChangeListener { _, marcado -> appPrefs.pocketMaisRapido = marcado }
    }

    /** Toque na linha "Pocket mais rápido" (em Avançado): liga ou desliga o switch, que grava no listener. */
    fun alternarPocketMaisRapido() {
        swPocketMaisRapido.toggle()
    }

    private fun mostrar(qualidade: QualidadeDaVoz) {
        val antes = carregando
        carregando = true
        chips.check(porChip.entries.first { it.value == qualidade }.key)
        carregando = antes
        tvAviso.visibility = if (qualidade.avisaPausar) View.VISIBLE else View.GONE
        ViewCompat.setStateDescription(chips, activity.getString(rotuloDaQualidade(qualidade)))
    }
}
