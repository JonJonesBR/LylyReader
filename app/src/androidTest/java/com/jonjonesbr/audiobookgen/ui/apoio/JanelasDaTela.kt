package com.jonjonesbr.audiobookgen.ui.apoio

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Janelas da tela como o UiAutomation as enxerga (rb-46 U4). Usado pelo arraste da lista, que desvia de menus
 * flutuantes do sistema, e pelo diagnóstico do C03.
 */
object JanelasDaTela {

    /**
     * Lê as janelas com FLAG_RETRIEVE_INTERACTIVE_WINDOWS ligada só durante a leitura, e devolve a flag como estava,
     * porque a UiAutomation é compartilhada pelos testes seguintes do processo. Se a flag ou a leitura falharem, avisa
     * por [relatarErro] e segue (a leitura é melhor esforço): sem janelas, a lista vazia.
     */
    fun ler(relatarErro: (String) -> Unit = {}): List<AccessibilityWindowInfo> {
        val automacao = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flagsAntes = runCatching { automacao.serviceInfo?.flags }.getOrNull()
        runCatching {
            val info = automacao.serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automacao.serviceInfo = info
            }
        }.onFailure { relatarErro("flag de janelas não configurada: ${it.message}") }
        try {
            return runCatching { automacao.windows.orEmpty() }
                .onFailure { relatarErro("janelas indisponíveis: ${it.message}") }
                .getOrDefault(emptyList())
        } finally {
            if (flagsAntes != null) {
                runCatching {
                    automacao.serviceInfo?.let { info ->
                        info.flags = flagsAntes
                        automacao.serviceInfo = info
                    }
                }
            }
        }
    }
}
