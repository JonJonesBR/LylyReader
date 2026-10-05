package com.jonjonesbr.audiobookgen.domain

const val RITMO_CONVERSAO_MIN = 50
const val RITMO_CONVERSAO_MAX = 200

/** Ritmo (%) a partir da posição de um SeekBar que vai de 0 até [RITMO_CONVERSAO_MAX] − [RITMO_CONVERSAO_MIN]. */
fun ritmoDaPosicao(posicao: Int): Int = (posicao + RITMO_CONVERSAO_MIN).coerceIn(RITMO_CONVERSAO_MIN, RITMO_CONVERSAO_MAX)

/** Posição do SeekBar para um ritmo (%); ritmos fora da faixa são limitados. */
fun posicaoDoRitmo(ritmo: Int): Int = ritmo.coerceIn(RITMO_CONVERSAO_MIN, RITMO_CONVERSAO_MAX) - RITMO_CONVERSAO_MIN
