package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.chip.ChipGroup
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo

/**
 * "Ritmo da narração" nos Ajustes › Som: Calma, Natural ou Fluida (padrão), num seletor único. Grava
 * [AppPrefs.estiloDeRitmo] ao tocar num chip. A Activity chama [carregar] e [configurar] (ver SettingsActivity).
 */
class AjustesRitmoController(private val activity: AppCompatActivity) {
    private val appPrefs by lazy { AppPrefs(activity) }
    private val chips: ChipGroup = activity.findViewById(R.id.chipsEstiloRitmo)
    private val tvDescricao: TextView = activity.findViewById(R.id.tvEstiloRitmoDesc)
    private val porChip = linkedMapOf(
        R.id.chipRitmoCalma to EstiloDeRitmo.CALMA,
        R.id.chipRitmoNatural to EstiloDeRitmo.NATURAL,
        R.id.chipRitmoFluida to EstiloDeRitmo.FLUIDA
    )
    // Evita gravar de volta o que acabou de ser lido (ou o eco de check() dentro da própria tela).
    private var carregando = false

    /** Marca o estilo salvo (sem gravar nada). */
    fun carregar() = mostrar(appPrefs.estiloDeRitmo)

    fun configurar() {
        activity.findViewById<View>(R.id.tvAjudaNarracao)
            .setOnClickListener { HelpActivity.start(activity, HelpTopico.NARRACAO) }
        chips.setOnCheckedStateChangeListener { _, ids ->
            if (carregando) return@setOnCheckedStateChangeListener
            val estilo = ids.firstOrNull()?.let { porChip[it] } ?: return@setOnCheckedStateChangeListener
            appPrefs.estiloDeRitmo = estilo
            mostrar(estilo)
        }
    }

    private fun mostrar(estilo: EstiloDeRitmo) {
        val antes = carregando
        carregando = true
        chips.check(porChip.entries.first { it.value == estilo }.key)
        carregando = antes
        tvDescricao.text = activity.getString(descricaoDoEstilo(estilo))
        ViewCompat.setStateDescription(chips, activity.getString(rotuloDoEstilo(estilo)))
    }
}
