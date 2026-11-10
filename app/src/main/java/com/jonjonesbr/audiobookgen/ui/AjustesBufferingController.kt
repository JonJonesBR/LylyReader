package com.jonjonesbr.audiobookgen.ui

import android.content.res.ColorStateList
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs

/**
 * Paralelismo do Kokoro nos Ajustes › Avançado (1 ou 2 sínteses ao mesmo tempo). O pré-carregamento e o
 * paralelismo do Supertonic saíram da tela: o pré-carregamento é sempre automático e o Supertonic usa uma vaga.
 * Extraído da [SettingsActivity]; a Activity chama [carregar] (ao montar a tela e depois de restaurar) e
 * [configurar] (uma vez, com os listeners).
 */
class AjustesBufferingController(private val activity: AppCompatActivity) {
    private val appPrefs by lazy { AppPrefs(activity) }

    private val btnParalelismoPadrao: Button = activity.findViewById(R.id.btnParalelismoPadrao)
    private val btnParalelismoReduzido: Button = activity.findViewById(R.id.btnParalelismoReduzido)
    private val botoesParalelismo get() = listOf(
        btnParalelismoPadrao to false, btnParalelismoReduzido to true
    )

    /** Mostra os valores salvos nos botões (sem gravar nada). */
    fun carregar() {
        atualizarBotoesParalelismo(appPrefs.paralelismoKokoroReduzido)
    }

    fun configurar() {
        for ((btn, reduzido) in botoesParalelismo) {
            btn.setOnClickListener {
                appPrefs.paralelismoKokoroReduzido = reduzido
                atualizarBotoesParalelismo(reduzido)
            }
        }
    }

    private fun atualizarBotoesParalelismo(reduzido: Boolean) {
        for ((btn, valor) in botoesParalelismo) {
            val sel = valor == reduzido
            btn.setTextColor(ContextCompat.getColor(activity,
                if (sel) R.color.color_primary else R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }
}
