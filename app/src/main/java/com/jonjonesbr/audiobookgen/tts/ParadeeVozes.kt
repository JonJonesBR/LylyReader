package com.jonjonesbr.audiobookgen.tts

/**
 * Vozes do Paradee-8M (pt-BR), que substituem o Kokoro nos ids `kokoro-pf-dora` e `kokoro-pm-alex`.
 * Os ids continuam os mesmos; só o motor de síntese muda (ver planejamento/PARADEE_FASE1_PLANO.md).
 */
internal object ParadeeVozes {
    const val PACOTE_ID = "paradee-ptbr"
    const val REVISAO_CACHE = "paradee-ptbr-1.2-int8-espeak1.52.0"
    const val LICENCA = "Apache-2.0"
    const val SAMPLE_RATE = 24000

    data class Spec(
        val voiceId: String,
        val arquivo: String,
        val sha256: String,
        val bytes: Long,
        val vozEspeak: String = "pt-br",
        val indiceVoz: Int = -1,
    )

    val todas: List<Spec> = listOf(
        Spec(
            voiceId = "kokoro-pf-dora",
            arquivo = "paradee_dora_int8.onnx",
            sha256 = "2020d311195a3a73d2db8cf3e1637c92a1192cfee28766bd36fc1b6971d38e06",
            bytes = 8456320L,
        ),
        Spec(
            voiceId = "kokoro-pm-alex",
            arquivo = "paradee_alex_int8.onnx",
            sha256 = "936510430f3c68d2965f610ffb0bb85583335cdc7f5652ca7de07aabd91f06b2",
            bytes = 8461438L,
        ),
    )

    fun spec(voiceId: String): Spec? = todas.firstOrNull { it.voiceId == voiceId }

    fun ehParadee(voiceId: String): Boolean = spec(voiceId) != null
}
