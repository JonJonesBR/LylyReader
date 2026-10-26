package com.jonjonesbr.audiobookgen.domain

/**
 * Parágrafo do texto que corresponde à posição [posicaoMs] do audiobook gerado (destaque aproximado).
 *
 * Com as marcas de capítulo do MP3 batendo com os capítulos do texto (mesma quantidade), localiza o capítulo pela
 * marca e só dentro dele usa a proporção do tempo; o erro fica restrito a um capítulo. Sem marcas compatíveis, usa a
 * proporção do livro inteiro. Devolve -1 sem texto ou sem duração.
 */
fun paragrafoNoAudiobook(
    posicaoMs: Long,
    duracaoMs: Long,
    capitulosAudio: List<CapituloAudio>,
    texto: TextoExtraido,
): Int {
    val paragrafos = texto.paragrafos
    if (paragrafos.isEmpty() || duracaoMs <= 0L) return -1
    val capitulos = texto.capitulos
    if (capitulosAudio.size > 1 && capitulosAudio.size == capitulos.size) {
        val i = capitulosAudio.indexOfLast { it.inicioMs <= posicaoMs }.coerceAtLeast(0)
        val audio = capitulosAudio[i]
        val cap = capitulos[i]
        val inicio = cap.indiceParagrafoInicio.coerceIn(0, paragrafos.lastIndex)
        val fim = cap.indiceParagrafoFim.coerceIn(inicio, paragrafos.lastIndex)
        val dentro = if (audio.fimMs > audio.inicioMs) {
            ((posicaoMs - audio.inicioMs).toDouble() / (audio.fimMs - audio.inicioMs)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val charInicio = paragrafos[inicio].charOffset
        val charFim = paragrafos[fim].charOffset + paragrafos[fim].texto.length
        val alvo = charInicio + dentro * (charFim - charInicio)
        return (inicio..fim).lastOrNull { paragrafos[it].charOffset <= alvo } ?: inicio
    }
    val alvo = (posicaoMs.toDouble() / duracaoMs).coerceIn(0.0, 1.0) * texto.totalChars
    return paragrafos.indexOfLast { it.charOffset <= alvo }.coerceAtLeast(0)
}
