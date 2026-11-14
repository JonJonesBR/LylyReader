package com.jonjonesbr.audiobookgen.tts

/**
 * Vozes do Paradee-8M (pt-BR), que substituem o Kokoro nos ids `kokoro-pf-dora` e `kokoro-pm-alex`.
 * Os ids continuam os mesmos; só o motor de síntese muda (ver planejamento/PARADEE_FASE1_PLANO.md).
 */
internal object ParadeeVozes {
    const val PACOTE_ID = "paradee-ptbr"
    const val PACOTE_ID_V13 = "paradee-v13"
    const val REVISAO_CACHE = "paradee-ptbr-1.2-int8-espeak1.52.0"
    const val REVISAO_CACHE_V13 = "paradee-v1.3-int8-espeak1.52.0"
    const val LICENCA = "Apache-2.0"
    const val SAMPLE_RATE = 24000
    const val ARQUIVO_V13 = "paradee_int8.onnx"
    const val SHA256_V13 = "f68ed87562d040693dd0dd34e99c11b35ca24294e2149b840503bd1f25a2199f"
    const val BYTES_V13 = 8462503L
    const val URL_MODELO_V13 =
        "https://huggingface.co/sahilmahendrakar/Paradee-8M-v1.3/resolve/" +
            "b569f5efb789af5ba643a44af348427242ceb784/onnx/paradee_int8.onnx"

    val PACOTES: List<String> = listOf(PACOTE_ID, PACOTE_ID_V13)

    data class Spec(
        val voiceId: String,
        val arquivo: String,
        val sha256: String,
        val bytes: Long,
        val vozEspeak: String = "pt-br",
        val indiceVoz: Int = -1,
        val pacoteId: String = PACOTE_ID,
        val url: String? = null,
        val revisaoCache: String = REVISAO_CACHE,
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
        Spec(
            voiceId = "kokoro-en-af-heart",
            arquivo = ARQUIVO_V13,
            sha256 = SHA256_V13,
            bytes = BYTES_V13,
            vozEspeak = "en-us",
            indiceVoz = 0,
            pacoteId = PACOTE_ID_V13,
            url = URL_MODELO_V13,
            revisaoCache = REVISAO_CACHE_V13,
        ),
        Spec(
            voiceId = "kokoro-en-bf-isabella",
            arquivo = ARQUIVO_V13,
            sha256 = SHA256_V13,
            bytes = BYTES_V13,
            // No espeak-ng 1.52.0, `lang/gmw/en` é o britânico (`language en-gb`) e atende por `"en"`.
            vozEspeak = "en",
            indiceVoz = 1,
            pacoteId = PACOTE_ID_V13,
            url = URL_MODELO_V13,
            revisaoCache = REVISAO_CACHE_V13,
        ),
        Spec(
            voiceId = "kokoro-en-am-puck",
            arquivo = ARQUIVO_V13,
            sha256 = SHA256_V13,
            bytes = BYTES_V13,
            vozEspeak = "en-us",
            indiceVoz = 2,
            pacoteId = PACOTE_ID_V13,
            url = URL_MODELO_V13,
            revisaoCache = REVISAO_CACHE_V13,
        ),
        Spec(
            voiceId = "kokoro-en-bm-fable",
            arquivo = ARQUIVO_V13,
            sha256 = SHA256_V13,
            bytes = BYTES_V13,
            vozEspeak = "en",
            indiceVoz = 3,
            pacoteId = PACOTE_ID_V13,
            url = URL_MODELO_V13,
            revisaoCache = REVISAO_CACHE_V13,
        ),
        Spec(
            voiceId = "kokoro-em-alex",
            arquivo = ARQUIVO_V13,
            sha256 = SHA256_V13,
            bytes = BYTES_V13,
            vozEspeak = "es",
            indiceVoz = 7,
            pacoteId = PACOTE_ID_V13,
            url = URL_MODELO_V13,
            revisaoCache = REVISAO_CACHE_V13,
        ),
    )

    fun spec(voiceId: String): Spec? = todas.firstOrNull { it.voiceId == voiceId }

    fun ehParadee(voiceId: String): Boolean = spec(voiceId) != null

    fun specsDoPacote(pacoteId: String): List<Spec> = todas.filter { it.pacoteId == pacoteId }

    fun arquivosDoPacote(pacoteId: String): List<Spec> = specsDoPacote(pacoteId).distinctBy { it.arquivo }

    fun bytesDoPacote(pacoteId: String): Long = arquivosDoPacote(pacoteId).sumOf { it.bytes }

    fun revisaoCache(voiceId: String): String = spec(voiceId)?.revisaoCache ?: REVISAO_CACHE
}
