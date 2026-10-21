package com.jonjonesbr.audiobookgen.util

import android.content.res.Configuration
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Em paisagem a altura é curta (~360dp): uma folha que abre recolhida corta o conteúdo e
 * deixa o restante inacessível. Por isso, nessa orientação, a folha abre já expandida.
 * Em retrato o comportamento não muda.
 */
fun precisaExpandirFolha(orientacao: Int): Boolean = orientacao == Configuration.ORIENTATION_LANDSCAPE

/** Aplica [precisaExpandirFolha] ao [BottomSheetDialog]. Chamar depois de [BottomSheetDialog.setContentView]. */
fun BottomSheetDialog.expandirEmPaisagem() {
    if (!precisaExpandirFolha(context.resources.configuration.orientation)) return
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
    behavior.skipCollapsed = true
}
