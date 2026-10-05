package com.jonjonesbr.audiobookgen.util

/** Presets de tempo (minutos) da soneca — usados no slider do diálogo e no botão da notificação. */
@Suppress("MagicNumber")
val PRESETS_SONECA_MIN = intArrayOf(5, 10, 20, 30, 60, 90, 120)

/**
 * Próximo preset no ciclo do botão de soneca da notificação: 5→10→20→30→60→90→120→desligado→5…
 * [atualMin] é a duração TOTAL (minutos) da soneca vigente (não o tempo restante, que só
 * decresce) — null/0 significa desligada. Se [atualMin] não for um preset exato (ex.: valor
 * personalizado via slider), avança pro primeiro preset MAIOR que ele. Retorna 0 pra "desligar".
 */
fun proximoPresetSonecaMin(atualMin: Int?, presets: IntArray = PRESETS_SONECA_MIN): Int {
    if (atualMin == null || atualMin <= 0) return presets.first()
    return presets.firstOrNull { it > atualMin } ?: 0
}
