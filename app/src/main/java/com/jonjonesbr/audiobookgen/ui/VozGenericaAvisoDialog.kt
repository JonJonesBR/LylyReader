package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.dpToPx

/**
 * Explica a voz-coringa "padrão do motor" (ex.: a voz "NOT_SET" do MultiTTS, que delega a
 * escolha real de voz/idioma para dentro do próprio app do motor) — era um Toast que fechava
 * rápido demais para dar tempo de ler; virou diálogo com "OK" e "não mostrar mais" (lembrado em
 * [AppPrefs.avisoVozGenericaDispensado]).
 */
object VozGenericaAvisoDialog {
    private const val PADDING_HORIZONTAL_DP = 24
    private const val PADDING_TOPO_DP = 8

    fun mostrarSeNecessario(activity: Activity) {
        val prefs = AppPrefs(activity)
        if (prefs.avisoVozGenericaDispensado) return

        val checkbox = CheckBox(activity).apply {
            setText(R.string.aviso_voz_generica_nao_mostrar_mais)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padH = activity.dpToPx(PADDING_HORIZONTAL_DP)
            setPadding(padH, activity.dpToPx(PADDING_TOPO_DP), padH, 0)
            addView(checkbox)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.aviso_voz_generica_titulo)
            .setMessage(R.string.aviso_voz_generica_motor)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (checkbox.isChecked) prefs.avisoVozGenericaDispensado = true
            }
            .setCancelable(false)
            .show()
    }
}
