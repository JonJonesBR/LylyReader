package com.jonjonesbr.audiobookgen.tts

/**
 * Codifica/decodifica o ID composto de uma voz do motor TTS nativo Android:
 * `"android::<enginePackage>::<voiceName>"`.
 *
 * O delimitador `::` é seguro porque nomes de pacote Android só permitem
 * `[a-zA-Z0-9_.]` — nunca contêm `:`. O decode localiza apenas o PRIMEIRO `::` após o
 * prefixo: tudo antes é o pacote do motor, tudo depois (mesmo que contenha `::`) é o nome
 * da voz — preserva nomes de voz atípicos sem quebrar o parsing.
 */
object AndroidVoiceId {
    private const val PREFIX = "android::"

    fun isAndroidVoice(voiceId: String): Boolean = voiceId.startsWith(PREFIX)

    fun encode(enginePackage: String, voiceName: String): String =
        "$PREFIX$enginePackage::$voiceName"

    /** Retorna (enginePackage, voiceName), ou null se [voiceId] não for uma voz Android. */
    @Suppress("ReturnCount") // early-returns de guarda são mais claros que aninhar if/else aqui
    fun decode(voiceId: String): Pair<String, String>? {
        if (!isAndroidVoice(voiceId)) return null
        val rest = voiceId.removePrefix(PREFIX)
        val idx = rest.indexOf("::")
        if (idx < 0) return null
        return rest.substring(0, idx) to rest.substring(idx + 2)
    }
}
