package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache
import com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineDiscovery
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class VoiceSelectionDelegate(
    private val activity: AppCompatActivity,
    private val onVoiceSelected: (VoiceOption) -> Unit,
    private val onVoicePreview: (VoiceOption, onFinished: () -> Unit) -> Unit,
    private val obterVozAtualId: () -> String,
    private val restorable: Boolean = true,
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
        val combinedVoices = VoiceCatalog.forVoiceSelection()
        val fm = activity.supportFragmentManager
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
                restorable = restorable
            )
        }.show(fm, "voice_bottom_sheet")
    }

    fun abrirFluxoVozAndroid() {
        val engines = AndroidTtsEngineDiscovery.listEngines(activity)
        if (engines.isEmpty()) {
            Toast.makeText(activity, R.string.erro_nenhum_motor_tts, Toast.LENGTH_SHORT).show()
            return
        }
        if (engines.size == 1) {
            carregarVozesAndroid(engines[0].packageName)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialogo_escolher_motor_titulo)
                .setItems(engines.map { it.label }.toTypedArray()) { _, which ->
                    carregarVozesAndroid(engines[which].packageName)
                }
                .show()
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
}
