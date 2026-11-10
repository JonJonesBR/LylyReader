package com.jonjonesbr.audiobookgen.domain

/** Fator de [EstiloDeRitmo.FLUIDA]: as pausas planejadas como são (~0,65 s entre frases). */
private const val FATOR_FLUIDA = 1.0f

/**
 * Fator de [EstiloDeRitmo.NATURAL] (padrão): pausas de narrador. Com as margens de 0,14 s, dá
 * ~0,9 s entre frases e ~1,6 s entre parágrafos.
 */
private const val FATOR_NATURAL = 1.45f

/** Fator de [EstiloDeRitmo.CALMA]: pausas longas, para ouvir com calma. */
private const val FATOR_CALMA = 1.9f

/**
 * Ritmo da narração na leitura guiada: quanto tempo a voz respira entre frases e parágrafos. Um seletor único
 * (Ajustes › Som e diálogo do leitor). [fator] multiplica as pausas de [PausasNaturais]; [entonacaoContinua]
 * liga a entonação contínua (frases seguidas vão juntas ao motor) só nos motores Supertonic e Pocket.
 * Regra pura, sem Android: a migração das preferências antigas também fica aqui.
 */
enum class EstiloDeRitmo(val id: String, val fator: Float, val entonacaoContinua: Boolean) {
    CALMA("calma", FATOR_CALMA, false),
    NATURAL("natural", FATOR_NATURAL, false),
    FLUIDA("fluida", FATOR_FLUIDA, true);

    companion object {
        /** Estilo de quem nunca escolheu: Natural. */
        val PADRAO = NATURAL

        /** Estilo gravado pelo [id]; `null` ou desconhecido (preferência corrompida) cai no [PADRAO]. */
        fun deId(id: String?): EstiloDeRitmo = entries.firstOrNull { it.id == id } ?: PADRAO

        /**
         * Estilo da primeira leitura de quem já tinha ajustes antigos: a entonação contínua ligada ou a pausa
         * natural desligada de propósito indicam o modo "Fluida" (o ritmo de antes). Sem isso, [PADRAO].
         */
        fun migrar(entonacaoContinuaLigada: Boolean, pausasNaturaisDesligadaExplicita: Boolean): EstiloDeRitmo =
            if (entonacaoContinuaLigada || pausasNaturaisDesligadaExplicita) FLUIDA else NATURAL
    }
}
