package com.jonjonesbr.audiobookgen.ui

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Abaixo disso a tela aparece direto, sem o aviso de carregando. */
internal const val ATRASO_AVISO_CARREGAMENTO_MS = 300L

/** Medidas do aviso (em dp, e o texto em sp): o espaçamento do conteúdo do diálogo. */
internal const val PREENCHIMENTO_LATERAL_AVISO_DP = 24
internal const val PREENCHIMENTO_VERTICAL_AVISO_DP = 20
internal const val PREENCHIMENTO_INFERIOR_AVISO_DP = 12
internal const val ESPACO_TEXTO_AVISO_DP = 16
internal const val TAMANHO_TEXTO_AVISO_SP = 15f

/**
 * Roda [trabalho] e, se ele passar de [ATRASO_AVISO_CARREGAMENTO_MS], mostra o aviso [mensagem] até terminar.
 * Sem o aviso, a tela parecia travada enquanto a lista de vozes esperava o catálogo. O atraso evita que o
 * aviso pisque toda vez que o resultado já está pronto (o caso comum).
 */
internal suspend fun <T> AppCompatActivity.comAguardeCarregamento(mensagem: Int, trabalho: suspend () -> T): T {
    val act = this
    return coroutineScope {
        var dialogo: AlertDialog? = null
        val aviso = launch {
            delay(ATRASO_AVISO_CARREGAMENTO_MS)
            val conteudo = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dpAguarde(PREENCHIMENTO_LATERAL_AVISO_DP),
                    dpAguarde(PREENCHIMENTO_VERTICAL_AVISO_DP),
                    dpAguarde(PREENCHIMENTO_LATERAL_AVISO_DP),
                    dpAguarde(PREENCHIMENTO_INFERIOR_AVISO_DP)
                )
                addView(ProgressBar(act).apply { isIndeterminate = true })
                addView(TextView(act).apply {
                    setText(mensagem)
                    textSize = TAMANHO_TEXTO_AVISO_SP
                    setPadding(dpAguarde(ESPACO_TEXTO_AVISO_DP), 0, 0, 0)
                })
            }
            dialogo = act.mostrarDialogo(
                AlertDialog.Builder(act).setView(conteudo).setCancelable(false)
            )
        }
        try {
            trabalho()
        } finally {
            aviso.cancel()
            dialogo?.dismiss()
        }
    }
}

private fun AppCompatActivity.dpAguarde(v: Int) = (v * resources.displayMetrics.density).toInt()
