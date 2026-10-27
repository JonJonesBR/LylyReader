package com.jonjonesbr.audiobookgen.domain

/** Início de um trecho no MP3 gerado e o começo do texto dele (gravados pela conversão, ver core_processor_android). */
data class TrechoAudio(val inicioMs: Long, val texto: String)

/** Lê o JSON de trechos devolvido pela conversão; lista vazia se faltar ou estiver ilegível. */
fun trechosDeJson(json: String?): List<TrechoAudio> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val inicio = o.optLong("inicio_ms", -1L)
            val texto = o.optString("texto", "")
            if (inicio < 0 || texto.isBlank()) null else TrechoAudio(inicio, texto)
        }
    }.getOrDefault(emptyList())
}

private val NAO_PALAVRA = Regex("[^\\p{L}\\p{N}]+")
private const val PALAVRAS_DA_CHAVE = 6
private const val PALAVRAS_CHAVE_CURTA = 3
private const val PALAVRAS_MINIMAS = 2
/** Quantos parágrafos à frente procurar cada trecho (o texto pode ter partes que não viraram áudio). */
private const val JANELA_DE_BUSCA = 300

private fun palavras(texto: String): List<String> =
    texto.lowercase().split(NAO_PALAVRA).filter { it.isNotEmpty() }

/**
 * Âncoras (início em ms → parágrafo) para o destaque exato: cada trecho do áudio é procurado no texto pelas primeiras
 * palavras, sempre para frente, a partir do último encontrado. Trecho que não for achado (texto normalizado de outro
 * jeito, parte removida) fica sem âncora e vale a anterior.
 */
fun ancorasDeSincronia(trechos: List<TrechoAudio>, texto: TextoExtraido): List<Pair<Long, Int>> {
    if (trechos.isEmpty() || texto.paragrafos.isEmpty()) return emptyList()
    val normalizados = texto.paragrafos.map { " " + palavras(it.texto).joinToString(" ") + " " }
    val ancoras = mutableListOf<Pair<Long, Int>>()
    var cursor = 0
    for (trecho in trechos) {
        val todas = palavras(trecho.texto)
        if (todas.size < PALAVRAS_MINIMAS) continue
        val ate = minOf(normalizados.lastIndex, cursor + JANELA_DE_BUSCA)
        // Chave longa primeiro; a curta cobre o trecho que começa no fim de um parágrafo e segue no próximo.
        val achado = listOf(PALAVRAS_DA_CHAVE, PALAVRAS_CHAVE_CURTA).firstNotNullOfOrNull { n ->
            val alvo = " " + todas.take(n).joinToString(" ") + " "
            (cursor..ate).firstOrNull { normalizados[it].contains(alvo) }
        } ?: continue
        ancoras += trecho.inicioMs to achado
        cursor = achado
    }
    return ancoras
}

/**
 * Parágrafo do texto que corresponde à posição [posicaoMs] do audiobook gerado.
 *
 * Com [ancoras] (audiobooks que gravaram o início de cada trecho), o início de cada trecho é exato, e entre um trecho e
 * o seguinte o destaque avança pela proporção do tempo (um trecho pode ter vários parágrafos: sem isso o destaque
 * ficava parado no primeiro deles até o próximo trecho). Sem âncoras, é aproximado:
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
    ancoras: List<Pair<Long, Int>> = emptyList(),
): Int {
    val paragrafos = texto.paragrafos
    if (paragrafos.isEmpty() || duracaoMs <= 0L) return -1
    val i = ancoras.indexOfLast { it.first <= posicaoMs }
    if (i >= 0) return entreAncoras(posicaoMs, ancoras[i], ancoras.getOrNull(i + 1), duracaoMs, texto)
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

/** Parágrafo entre a âncora [de] e a seguinte [ate] (ou o fim do áudio e do texto), pela proporção do tempo. */
private fun entreAncoras(
    posicaoMs: Long,
    de: Pair<Long, Int>,
    ate: Pair<Long, Int>?,
    duracaoMs: Long,
    texto: TextoExtraido,
): Int {
    val paragrafos = texto.paragrafos
    val inicio = de.second.coerceIn(0, paragrafos.lastIndex)
    val fimMs = ate?.first ?: duracaoMs
    val fim = (ate?.second ?: paragrafos.lastIndex).coerceIn(inicio, paragrafos.lastIndex)
    if (fim == inicio || fimMs <= de.first) return inicio
    val dentro = ((posicaoMs - de.first).toDouble() / (fimMs - de.first)).coerceIn(0.0, 1.0)
    val charInicio = paragrafos[inicio].charOffset
    // Até a próxima âncora, o texto vai até o começo do parágrafo dela; sem próxima, até o fim do livro.
    val charFim = if (ate != null) paragrafos[fim].charOffset else texto.totalChars
    val alvo = charInicio + dentro * (charFim - charInicio)
    return (inicio..fim).lastOrNull { paragrafos[it].charOffset <= alvo } ?: inicio
}
