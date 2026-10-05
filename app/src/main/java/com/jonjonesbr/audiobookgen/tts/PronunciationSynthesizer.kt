package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary

/**
 * Aplica o dicionário de pronúncia do usuário ao texto antes de qualquer motor sintetizar.
 * A chave do cache de áudio usa o texto já trocado, então mudar o dicionário refaz só o que mudou.
 */
class PronunciationSynthesizer(
    private val inner: TtsSynthesizer,
    private val dicionario: () -> PronunciationDictionary
) : TtsSynthesizer {
    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? =
        inner.synthesize(dicionario().apply(texto), voz, ritmo)

    override fun ultimoErro(): String? = inner.ultimoErro()
}
