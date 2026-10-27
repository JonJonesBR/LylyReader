package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineDiscovery
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceSelectionDelegate(
    private val activity: AppCompatActivity,
    private val onVoiceSelected: (VoiceOption) -> Unit,
    private val onVoicePreview: (VoiceOption, onFinished: () -> Unit) -> Unit,
    private val obterVozAtualId: () -> String,
    private val restorable: Boolean = true,
    private val onCloneVoiceRequested: (() -> Unit)? = null,
) {

    private val voiceDownloadFlow by lazy { VoiceDownloadFlow(activity) }

    init {
        if (restorable) {
            activity.supportFragmentManager.setFragmentResultListener(
                VoiceBottomSheet.RESULT_KEY, activity
            ) { _, result ->
                when (result.getString(VoiceBottomSheet.RESULT_ACTION)) {
                    VoiceBottomSheet.ACTION_SELECT -> VoiceBottomSheet
                        .voiceFromBundle(result.getBundle(VoiceBottomSheet.RESULT_VOICE))
                        ?.let(::selecionarVozRestaurada)
                    VoiceBottomSheet.ACTION_ANDROID -> abrirFluxoVozAndroid()
                    VoiceBottomSheet.ACTION_CLONE -> onCloneVoiceRequested?.invoke()
                    VoiceBottomSheet.ACTION_PREVIEW -> VoiceBottomSheet
                        .voiceFromBundle(result.getBundle(VoiceBottomSheet.RESULT_VOICE))
                        ?.let { voice ->
                            onVoicePreview(voice) {
                                activity.supportFragmentManager.setFragmentResult(
                                    VoiceBottomSheet.PREVIEW_FINISHED_KEY,
                                    Bundle().apply {
                                        putString(VoiceBottomSheet.RESULT_VOICE, voice.id)
                                    }
                                )
                            }
                        }
                }
            }
        }
    }
    private fun selecionarVozRestaurada(voice: VoiceOption) {
        voiceDownloadFlow.verificarEBaixarSeNecessario(voice) {
            onVoiceSelected(voice)
            if (voice.engine == "android" && voice.isGenerico) {
                VozGenericaAvisoDialog.mostrarSeNecessario(activity)
            }
        }
    }

    fun abrirSeletorVozes() {
        // Montar a lista lê arquivos e pode esperar a reidratação do catálogo (até 5 s): isso roda
        // fora da thread principal, com o aviso "Carregando vozes…" enquanto termina.
        if (abrindoSeletor) return
        abrindoSeletor = true
        activity.lifecycleScope.launch {
            val combinedVoices = try {
                comAguarde(R.string.toast_carregando_vozes_android) {
                    withContext(Dispatchers.IO) {
                        VoiceCatalog.vozesVisiveis(VoiceCatalog.forVoiceSelection(), activity)
                    }
                }
            } finally {
                abrindoSeletor = false
            }
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return@launch
            VoiceBottomSheet().apply {
                configure(
                    voices = combinedVoices,
                    selectedVoiceId = obterVozAtualId(),
                    onVoiceSelected = { voice ->
                        voiceDownloadFlow.verificarEBaixarSeNecessario(voice) {
                            onVoiceSelected(voice)
                        }
                    },
                    onVoicePreview = onVoicePreview,
                    onAndroidVoiceRequested = { abrirFluxoVozAndroid() },
                    onCloneVoiceRequested = onCloneVoiceRequested,
                    restorable = restorable
                )
            }.show(fm, "voice_bottom_sheet")
        }
    }

    /** Impede dois seletores abertos quando o usuário toca de novo enquanto a lista carrega. */
    private var abrindoSeletor = false

    /**
     * Roda [trabalho] e, se ele passar de [ATRASO_AVISO_MS], mostra o aviso "Carregando vozes…" até terminar.
     * Mesma ideia do "Aguarde" da clonagem: sem o aviso, a tela parecia travada. O atraso evita que o
     * aviso pisque toda vez que a lista já está pronta (o caso comum).
     */
    private suspend fun <T> comAguarde(mensagem: Int, trabalho: suspend () -> T): T = coroutineScope {
        var dialogo: AlertDialog? = null
        val aviso = launch {
            delay(ATRASO_AVISO_MS)
            val conteudo = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(12))
                addView(ProgressBar(activity).apply { isIndeterminate = true })
                addView(TextView(activity).apply {
                    setText(mensagem)
                    textSize = 15f
                    setPadding(dp(16), 0, 0, 0)
                })
            }
            dialogo = activity.mostrarDialogo(
                AlertDialog.Builder(activity).setView(conteudo).setCancelable(false)
            )
        }
        try {
            trabalho()
        } finally {
            aviso.cancel()
            dialogo?.dismiss()
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun abrirFluxoVozAndroid() {
        val engines = AndroidTtsEngineDiscovery.listEngines(activity)
        if (engines.isEmpty()) {
            Toast.makeText(activity, R.string.erro_nenhum_motor_tts, Toast.LENGTH_SHORT).show()
            return
        }
        if (engines.size == 1) {
            carregarVozesAndroid(engines[0].packageName)
        } else {
            activity.mostrarDialogo(AlertDialog.Builder(activity)
                .setTitle(R.string.dialogo_escolher_motor_titulo)
                .setItems(engines.map { it.label }.toTypedArray()) { _, which ->
                    carregarVozesAndroid(engines[which].packageName)
                })
        }
    }

    fun carregarVozesAndroid(enginePackage: String) {
        val act = activity
        val fm = act.supportFragmentManager
        Toast.makeText(act, R.string.toast_carregando_vozes_android, Toast.LENGTH_SHORT).show()
        act.lifecycleScope.launch {
            val vozesAndroid = AndroidTtsEngineCache.listVoices(act, enginePackage)
            if (vozesAndroid.isEmpty()) {
                Toast.makeText(act, R.string.erro_nenhuma_voz_motor, Toast.LENGTH_SHORT).show()
                return@launch
            }
            VoiceBottomSheet().apply {
                configure(
                    voices = vozesAndroid,
                    selectedVoiceId = obterVozAtualId(),
                    onVoiceSelected = { voice ->
                        voiceDownloadFlow.verificarEBaixarSeNecessario(voice) {
                            onVoiceSelected(voice)
                            if (voice.isGenerico) VozGenericaAvisoDialog.mostrarSeNecessario(act)
                        }
                    },
                    onVoicePreview = onVoicePreview,
                    restorable = restorable
                )
            }.show(fm, "voice_bottom_sheet_android")
        }
    }

    private companion object {
        /** Abaixo disso a lista aparece direto, sem o aviso de carregando. */
        const val ATRASO_AVISO_MS = 300L
    }
}
