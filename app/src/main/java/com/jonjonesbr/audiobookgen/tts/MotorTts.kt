package com.jonjonesbr.audiobookgen.tts

/**
 * Motores de síntese de voz do app. O [id] é exatamente o valor já salvo nas preferências e usado
 * nos ids de voz, então não pode mudar. Hoje só [TtsSynthesizer] e o `VoiceCatalog` usam este
 * enum; os demais pontos ainda comparam strings e serão migrados aos poucos.
 */
enum class MotorTts(val id: String) {
    ONNX("onnx"),
    KOKORO("kokoro"),
    POCKET("pocket"),
    ANDROID("android"),
    ELEVENLABS("elevenlabs"),
    EDGE("edge"),
    GEMINI("gemini");

    companion object {
        /** Motor com esse id salvo, ou null quando não é um motor conhecido (ex.: motor Python). */
        fun deId(id: String): MotorTts? = values().firstOrNull { it.id == id }
    }
}
