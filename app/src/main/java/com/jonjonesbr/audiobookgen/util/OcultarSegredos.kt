package com.jonjonesbr.audiobookgen.util

private val SEGREDOS = listOf(
    // Parâmetro de URL (o Gemini recebe a chave em ?key=) e cabeçalhos de autenticação copiados em mensagens de erro.
    Regex("""(?i)([?&](?:key|api_key|apikey|token)=)[^&\s"']+"""),
    Regex("""(?i)((?:x-goog-api-key|xi-api-key)["']?\s*[:=]\s*["']?)[^\s"',}]+"""),
    Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}"""),
    // Formatos conhecidos de chave soltos no texto: Google (AIza…), OpenRouter/OpenAI (sk-…), ElevenLabs (sk_…).
    Regex("""()AIza[0-9A-Za-z_-]{30,}"""),
    Regex("""()sk-[A-Za-z0-9_-]{16,}"""),
    Regex("""()sk_[A-Za-z0-9]{16,}"""),
)

/** Troca chaves de API por "***" (mantém o nome do parâmetro): o log de erro pode ser compartilhado pelo usuário. */
fun ocultarSegredos(texto: String): String =
    SEGREDOS.fold(texto) { acc, regex -> acc.replace(regex) { it.groupValues[1] + "***" } }
