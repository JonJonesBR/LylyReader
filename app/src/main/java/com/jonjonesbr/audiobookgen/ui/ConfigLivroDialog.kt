package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.util.VELOCIDADES_GUIADAS
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.aplicarSnapVelocidade
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

private const val EPSILON_VELOCIDADE = 0.01f

/**
 * "Configurar este livro" — permite ver/mudar a voz e a velocidade da leitura guiada ANTES de
 * começar a ouvir. O sistema por-livro (T4.3, [GuidedBookPrefsStore]) já existia, mas só
 * gravava depois que o usuário mexia no seletor DURANTE a leitura guiada ativa; este diálogo
 * lê/escreve o mesmo store diretamente, sem precisar de uma sessão de
 * [com.jonjonesbr.audiobookgen.service.GuidedPlayerManager] ativa.
 */
object ConfigLivroDialog {

    fun mostrar(activity: AppCompatActivity, caminhoArquivo: String, onClonarVoz: () -> Unit = {}) {
        val appPrefs = AppPrefs(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_config_livro, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)

        val tvVoz = view.findViewById<TextView>(R.id.tvConfigVozAtual)
        val idsVelocidade = listOf(
            R.id.btnConfigVel075, R.id.btnConfigVel100, R.id.btnConfigVel125,
            R.id.btnConfigVel150, R.id.btnConfigVel175, R.id.btnConfigVel200
        )
        val botoesVelocidade = idsVelocidade.zip(VELOCIDADES_GUIADAS.toList()) { id, valor ->
            view.findViewById<Button>(id) to valor
        }
        val tvVelPersonalizada = view.findViewById<TextView>(R.id.tvConfigVelPersonalizada)
        val sliderVelocidade = view.findViewById<Slider>(R.id.sliderConfigVel)

        fun formatarVelocidade(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0×" else "${v}×"

        fun atualizarUi() {
            val efetivo = resolverEfetivo(activity, caminhoArquivo, appPrefs)
            val voz = VoiceCatalog.findAny(efetivo.voz)
                ?: VoiceCatalog.find(efetivo.motor, efetivo.voz)
                ?: VoiceCatalog.defaultFor(efetivo.motor)
            tvVoz.text = voz.displayLabel(activity)
            botoesVelocidade.forEach { (btn, valor) ->
                val selecionado = kotlin.math.abs(valor - efetivo.speedMult) < EPSILON_VELOCIDADE
                btn.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (selecionado) R.color.color_primary else R.color.color_on_surface
                    )
                )
            }
            tvVelPersonalizada.text = formatarVelocidade(efetivo.speedMult)
            sliderVelocidade.value = efetivo.speedMult.coerceIn(sliderVelocidade.valueFrom, sliderVelocidade.valueTo)
        }

        fun salvarVelocidade(valor: Float) {
            val atual = resolverEfetivo(activity, caminhoArquivo, appPrefs)
            GuidedBookPrefsStore.save(activity, caminhoArquivo, atual.voz, atual.motor, valor)
            atualizarUi()
        }

        view.findViewById<View>(R.id.itemConfigVoz).setOnClickListener {
            abrirSeletorDeVoz(activity, caminhoArquivo, appPrefs, ::atualizarUi, onClonarVoz)
        }
        botoesVelocidade.forEach { (btn, valor) -> btn.setOnClickListener { salvarVelocidade(valor) } }
        sliderVelocidade.addOnChangeListener { _, value, fromUser ->
            if (fromUser) tvVelPersonalizada.text = formatarVelocidade(value)
        }
        sliderVelocidade.addOnSliderTouchListener(
            criarTouchListenerVelocidade { salvarVelocidade(aplicarSnapVelocidade(it)) }
        )

        atualizarUi()
        dialog.show()
    }

    /** Só onStopTrackingTouch (soltar o dedo) importa aqui — extraído pra manter [mostrar]
     * dentro do limite de linhas do detekt. */
    private fun criarTouchListenerVelocidade(aoSoltar: (Float) -> Unit) = object : Slider.OnSliderTouchListener {
        @Suppress("EmptyFunctionBlock")
        override fun onStartTrackingTouch(slider: Slider) { }
        override fun onStopTrackingTouch(slider: Slider) { aoSoltar(slider.value) }
    }

    private fun abrirSeletorDeVoz(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        appPrefs: AppPrefs,
        aoConcluir: () -> Unit,
        onClonarVoz: () -> Unit
    ) {
        val efetivo = resolverEfetivo(activity, caminhoArquivo, appPrefs)
        VoiceSelectionDelegate(
            activity = activity,
            onVoiceSelected = { voz ->
                val atual = resolverEfetivo(activity, caminhoArquivo, appPrefs)
                GuidedBookPrefsStore.save(activity, caminhoArquivo, voz.id, voz.engine, atual.speedMult)
                aoConcluir()
            },
            onVoicePreview = { _, onFinished -> onFinished() },
            obterVozAtualId = { efetivo.voz },
            restorable = false,
            onCloneVoiceRequested = onClonarVoz
        ).abrirSeletorVozes()
    }

    /** Voz/motor/velocidade efetivos deste livro: override salvo, ou fallback pras prefs globais. */
    private fun resolverEfetivo(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        appPrefs: AppPrefs
    ): GuidedBookPrefsStore.BookPrefs {
        GuidedBookPrefsStore.load(activity, caminhoArquivo)?.let { return it }
        val motor = appPrefs.motorTts
        val voz = appPrefs.vozSelecionada ?: VoiceCatalog.defaultFor(motor).id
        return GuidedBookPrefsStore.BookPrefs(voz, motor, appPrefs.guidedSpeedMult)
    }
}
