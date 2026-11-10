package com.jonjonesbr.audiobookgen.ui

import androidx.annotation.StringRes
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo
import com.jonjonesbr.audiobookgen.domain.QualidadeDaVoz

/** Textos do ritmo e da qualidade, compartilhados por Ajustes e pelo diálogo do leitor (o domínio não usa R). */
@StringRes
internal fun rotuloDoEstilo(estilo: EstiloDeRitmo): Int = when (estilo) {
    EstiloDeRitmo.CALMA -> R.string.aj_ritmo_calma
    EstiloDeRitmo.NATURAL -> R.string.aj_ritmo_natural
    EstiloDeRitmo.FLUIDA -> R.string.aj_ritmo_fluida
}

/** Linha explicativa do estilo marcado. */
@StringRes
internal fun descricaoDoEstilo(estilo: EstiloDeRitmo): Int = when (estilo) {
    EstiloDeRitmo.CALMA -> R.string.aj_ritmo_calma_desc
    EstiloDeRitmo.NATURAL -> R.string.aj_ritmo_natural_desc
    EstiloDeRitmo.FLUIDA -> R.string.aj_ritmo_fluida_desc
}

@StringRes
internal fun rotuloDaQualidade(qualidade: QualidadeDaVoz): Int = when (qualidade) {
    QualidadeDaVoz.RAPIDA -> R.string.aj_qualidade_rapida
    QualidadeDaVoz.EQUILIBRADA -> R.string.aj_qualidade_equilibrada
    QualidadeDaVoz.ALTA -> R.string.aj_qualidade_alta
}
