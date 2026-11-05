package com.jonjonesbr.audiobookgen.ui

import android.content.res.ColorStateList
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs

/**
 * Buffering da leitura guiada nos Ajustes (rb-11): profundidade de prefetch e paralelismo do Kokoro e do
 * Supertonic. Extraído da [SettingsActivity]; as views são achadas aqui, e a Activity chama [carregar] (ao
 * montar a tela e depois de restaurar) e [configurar] (uma vez, com os listeners).
 */
class AjustesBufferingController(private val activity: AppCompatActivity) {
    private val appPrefs by lazy { AppPrefs(activity) }
    private val dialogos = DialogosAbertos(activity)

    private val seekBufferParagrafos: SeekBar = activity.findViewById(R.id.seekBufferParagrafos)
    private val tvBufferParagrafosValor: TextView = activity.findViewById(R.id.tvBufferParagrafosValor)
    private val btnParalelismoPadrao: Button = activity.findViewById(R.id.btnParalelismoPadrao)
    private val btnParalelismoReduzido: Button = activity.findViewById(R.id.btnParalelismoReduzido)
    private val botoesParalelismo get() = listOf(
        btnParalelismoPadrao to false, btnParalelismoReduzido to true
    )
    private val btnParalelismoSupertonicoPadrao: Button =
        activity.findViewById(R.id.btnParalelismoSupertonicoPadrao)
    private val btnParalelismoSupertonicoExperimental: Button =
        activity.findViewById(R.id.btnParalelismoSupertonicoExperimental)
    private val botoesParalelismoSupertonico get() = listOf(
        btnParalelismoSupertonicoPadrao to false, btnParalelismoSupertonicoExperimental to true
    )

    /** Mostra os valores salvos nos botões e no slider (sem gravar nada). */
    fun carregar() {
        val override = appPrefs.bufferParagrafosAdiante
        val progress = if (override == AppPrefs.BUFFER_ADIANTE_AUTOMATICO) {
            0
        } else {
            override.coerceIn(0, seekBufferParagrafos.max)
        }
        seekBufferParagrafos.progress = progress
        atualizarLabelBufferParagrafos(progress)
        atualizarBotoesParalelismo(appPrefs.paralelismoKokoroReduzido)
        atualizarBotoesParalelismoSupertonico(appPrefs.paralelismoSupertonicoAumentado)
    }

    fun configurar() {
        seekBufferParagrafos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                // Posição 0 = automático (sentinela); 1..8 = valor explícito, sobrepondo o
                // padrão interno de TODOS os motores (ver GuidedPlayerManager.profundidadePrefetchEfetiva).
                appPrefs.bufferParagrafosAdiante = if (progress == 0) {
                    AppPrefs.BUFFER_ADIANTE_AUTOMATICO
                } else {
                    progress
                }
                atualizarLabelBufferParagrafos(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        for ((btn, reduzido) in botoesParalelismo) {
            btn.setOnClickListener {
                appPrefs.paralelismoKokoroReduzido = reduzido
                atualizarBotoesParalelismo(reduzido)
            }
        }

        btnParalelismoSupertonicoPadrao.setOnClickListener {
            appPrefs.paralelismoSupertonicoAumentado = false
            atualizarBotoesParalelismoSupertonico(false)
        }
        btnParalelismoSupertonicoExperimental.setOnClickListener {
            // EXPERIMENTAL de verdade (thread-safety nativa nunca validada) — confirma antes
            // de ativar, mesmo padrão do aviso de licença na importação BYOM.
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_paralelismo_experimental_titulo)
                .setMessage(R.string.dialog_paralelismo_experimental_mensagem)
                .setPositiveButton(R.string.btn_ativar_mesmo_assim) { _, _ ->
                    appPrefs.paralelismoSupertonicoAumentado = true
                    atualizarBotoesParalelismoSupertonico(true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .let { dialogos.mostrar(it) }
        }
    }

    private fun atualizarBotoesParalelismoSupertonico(aumentado: Boolean) {
        for ((btn, valor) in botoesParalelismoSupertonico) {
            val sel = valor == aumentado
            btn.setTextColor(ContextCompat.getColor(activity,
                if (sel) R.color.color_primary else R.color.color_on_surface_muted))
            btn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity,
                if (sel) R.color.color_primary_surface else R.color.color_surface_variant))
        }
    }

    private fun atualizarLabelBufferParagrafos(progress: Int) {
        tvBufferParagrafosValor.text = if (progress == 0) {
            activity.getString(R.string.settings_buffer_paragrafos_valor_automatico)
        } else {
            activity.getString(R.string.settings_buffer_paragrafos_valor, progress)
        }
        ViewCompat.setStateDescription(seekBufferParagrafos, tvBufferParagrafosValor.text)
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
