package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.util.LanguageDetector

/**
 * Vozes multilíngues do Supertonic (sem sufixo `-pt`) que podem usar o pacote V1 quando o texto é
 * inglês. As demais (`-pt`, F2–F5, M2–M5) são sempre V2.
 */
private val VOZES_MULTILINGUES_COM_V1 = setOf("supertonic-f1", "supertonic-m1")

/**
 * Pacote do Supertonic que sintetiza [voiceId] para [texto]: "v1" ou "v2". Função pura e
 * determinística — o cache e o motor devem usar esta mesma regra.
 *
 * "v1" só para as vozes multilíngues f1/m1 quando o texto é detectado como inglês. Sem evidência
 * de inglês (texto vazio ou sem palavras conhecidas), ou para qualquer outra voz, é "v2".
 */
fun versaoSupertonic(voiceId: String, texto: String): String {
    if (voiceId !in VOZES_MULTILINGUES_COM_V1) return "v2"
    return if (LanguageDetector.detectLanguageOrNull(texto) == "en-US") "v1" else "v2"
}

/**
 * Versão que o motor e o cache usam de fato: a desejada por [versaoSupertonic], com fallback para
 * "v2" quando a desejada é "v1" mas o pacote V1 não está instalado ([v1Pronto] false). Ponto único
 * da regra: cache e motor chamam esta função com o mesmo (voiceId, texto).
 */
fun versaoEfetivaSupertonic(voiceId: String, texto: String, v1Pronto: Boolean): String {
    val desejada = versaoSupertonic(voiceId, texto)
    return if (desejada == "v1" && !v1Pronto) "v2" else desejada
}

/**
 * Código de idioma passado ao nativo ("en", "pt", "es") para a [versao] escolhida. O V1 é só
 * inglês: recebe sempre "en" (lang pt/es no V1 faria o modelo soletrar o texto). No V2, vozes
 * "-pt" forçam "pt"; as multilíngues seguem a detecção do texto.
 */
fun idiomaNativoSupertonic(voiceId: String, texto: String, versao: String): String {
    if (versao == "v1") return "en"
    if (voiceId.endsWith("-pt", ignoreCase = true)) return "pt"
    return when (LanguageDetector.detectLanguage(texto)) {
        "pt-BR" -> "pt"
        "es-ES" -> "es"
        else -> "en"
    }
}
