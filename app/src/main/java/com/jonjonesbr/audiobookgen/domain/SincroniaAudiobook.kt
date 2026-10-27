package com.jonjonesbr.audiobookgen.domain

import java.text.Normalizer

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

// Com \p{M}: livro gravado com acento separado da letra (NFD, comum em EPUB/PDF feito no Mac) não quebra a palavra.
private val PALAVRA = Regex("[\\p{L}\\p{M}\\p{N}]+")
private const val PALAVRAS_DA_CHAVE = 6
private const val PALAVRAS_CHAVE_CURTA = 3
private const val PALAVRAS_MINIMAS = 2
/** Tamanho máximo do texto de cada trecho gravado pela conversão (_SINCRONIA_TEXTO_MAX em core_processor_android). */
private const val TEXTO_GRAVADO_MAX = 80
/** Chave longa só vale se achada a até esta distância (em caracteres) do cursor. */
private const val JANELA_CHAVE_LONGA = 200_000
/** Chave curta casa com mais facilidade por acaso, então a janela é menor. */
private const val JANELA_CHAVE_CURTA = 20_000

/** Chaves tentadas em ordem, como (palavras puladas no começo do trecho, tamanho da chave). */
private val CHAVES_EM_ORDEM = listOf(
    0 to PALAVRAS_DA_CHAVE,
    1 to PALAVRAS_DA_CHAVE,
    0 to PALAVRAS_CHAVE_CURTA,
    1 to PALAVRAS_CHAVE_CURTA,
)

/**
 * Índice das palavras do livro inteiro: [texto] é " palavra1 palavra2 … " (palavras normalizadas, sem acento e em
 * minúsculas), e [origem] tem, para cada posição de [texto], o caractere global do livro onde começa a palavra dali.
 */
private class IndicePalavras(val texto: String, val origem: IntArray)

/** Minúsculas e sem acento: "Coração" e "coracao" viram a mesma palavra (NFKD também desfaz ligaduras, "ﬁ" -> "fi"). */
private fun normalizar(palavra: String): String =
    Normalizer.normalize(palavra.lowercase(), Normalizer.Form.NFKD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

/** Palavras de um trecho do áudio, normalizadas do mesmo jeito que o índice do livro. */
private fun palavrasNormalizadas(texto: String): List<String> =
    PALAVRA.findAll(texto).map { normalizar(it.value) }.filter { it.isNotEmpty() }.toList()

/**
 * Indexa todos os parágrafos juntos, para que uma chave possa atravessar a fronteira entre dois parágrafos. A posição
 * de cada palavra vem do texto original do parágrafo, então o caractere global continua certo mesmo com acento.
 */
private fun indexar(texto: TextoExtraido): IndicePalavras {
    val palavras = texto.paragrafos.flatMap { paragrafo ->
        PALAVRA.findAll(paragrafo.texto).map { m -> normalizar(m.value) to paragrafo.charOffset + m.range.first }
    }
    val tamanho = 1 + palavras.sumOf { it.first.length + 1 }
    val sb = StringBuilder(tamanho).append(' ')
    val origem = IntArray(tamanho)
    for ((palavra, inicio) in palavras) {
        val posicao = sb.length
        sb.append(palavra).append(' ')
        origem.fill(inicio, posicao, sb.length)
    }
    return IndicePalavras(sb.toString(), origem)
}

/**
 * Âncoras (início em ms → caractere global do livro) para o destaque exato: cada trecho do áudio é procurado no texto
 * pelas primeiras palavras, sempre para frente, a partir do último encontrado. Trecho que não for achado (texto
 * normalizado de outro jeito, parte removida) fica sem âncora e vale a anterior.
 */
fun ancorasDeSincronia(trechos: List<TrechoAudio>, texto: TextoExtraido): List<Pair<Long, Int>> {
    if (trechos.isEmpty() || texto.paragrafos.isEmpty()) return emptyList()
    val indice = indexar(texto)
    val ancoras = mutableListOf<Pair<Long, Int>>()
    var cursor = 0
    for (trecho in trechos) {
        val palavras = palavrasNormalizadas(trecho.texto)
        if (palavras.size < PALAVRAS_MINIMAS) continue
        // Texto gravado no limite de caracteres: a última palavra pode vir cortada e fica fora da chave.
        val cortado = trecho.texto.length >= TEXTO_GRAVADO_MAX && palavras.size > PALAVRAS_MINIMAS
        val achado = acharNoIndice(indice, if (cortado) palavras.dropLast(1) else palavras, cursor) ?: continue
        ancoras += trecho.inicioMs to achado.first
        cursor = achado.second
    }
    return ancoras
}

/**
 * Procura as palavras do trecho no índice, a partir de [cursor]. Devolve o caractere global do começo do trecho e o
 * novo cursor, ou null se nenhuma chave casar dentro da janela.
 */
private fun acharNoIndice(indice: IndicePalavras, palavras: List<String>, cursor: Int): Pair<Int, Int>? {
    for ((pulo, maximo) in CHAVES_EM_ORDEM) {
        // Trecho curto (título, fala breve) usa todas as palavras que tem; pular a 1ª só vale com chave de verdade.
        val tamanho = minOf(maximo, palavras.size - pulo)
        if (tamanho < (if (pulo == 0) PALAVRAS_MINIMAS else PALAVRAS_CHAVE_CURTA)) continue
        val alvo = " " + palavras.subList(pulo, pulo + tamanho).joinToString(" ") + " "
        val janela = if (tamanho >= PALAVRAS_DA_CHAVE) JANELA_CHAVE_LONGA else JANELA_CHAVE_CURTA
        val i = indice.texto.indexOfAte(alvo, cursor, cursor + janela)
        if (i < 0) continue
        // i é o espaço logo antes da primeira palavra da chave. Se a chave pulou a 1ª palavra do trecho (ela pode ser
        // um pedaço), o começo do trecho é o da palavra anterior, que termina nesse mesmo espaço.
        val caractere = indice.origem[if (pulo == 0) i + 1 else i]
        return caractere to i + 1
    }
    return null
}

/**
 * Como [String.indexOf], mas só considera começos até [ate]: um trecho que não está no livro não pode varrer o texto
 * inteiro (com milhares de trechos assim, o destaque demorava segundos para aparecer). [alvo] começa com espaço.
 */
private fun String.indexOfAte(alvo: String, de: Int, ate: Int): Int {
    val limite = minOf(ate, length - alvo.length)
    var i = indexOf(alvo[0], de)
    while (i in 0..limite) {
        if (regionMatches(i, alvo, 0, alvo.length)) return i
        i = indexOf(alvo[0], i + 1)
    }
    return -1
}

/**
 * Parágrafo do texto que corresponde à posição [posicaoMs] do audiobook gerado.
 *
 * Com [ancoras] (audiobooks que gravaram o início de cada trecho), cada âncora é um caractere exato do livro. Entre uma
 * âncora e a seguinte o caractere avança pela proporção do tempo, e o parágrafo é o que contém esse caractere. Assim o
 * destaque acompanha o texto mesmo quando um trecho começa no meio de um parágrafo. Sem âncoras (ou antes da primeira),
 * é aproximado:
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
    val i = ultimoAntes(ancoras.size) { ancoras[it].first <= posicaoMs }
    if (i >= 0) return paragrafoNaAncora(posicaoMs, ancoras, i, duracaoMs, texto)
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

/**
 * Parágrafo que contém o caractere estimado para [posicaoMs], entre a âncora [i] e a seguinte (ou o fim do áudio e do
 * texto), pela proporção do tempo. Se a seguinte não estiver depois no tempo, fica no caractere da atual.
 */
private fun paragrafoNaAncora(
    posicaoMs: Long,
    ancoras: List<Pair<Long, Int>>,
    i: Int,
    duracaoMs: Long,
    texto: TextoExtraido,
): Int {
    val (inicioMs, inicioChar) = ancoras[i]
    val (fimMs, fimChar) = ancoras.getOrNull(i + 1) ?: (duracaoMs to texto.totalChars)
    val caractere = if (fimMs <= inicioMs) {
        inicioChar.toDouble()
    } else {
        val dentro = ((posicaoMs - inicioMs).toDouble() / (fimMs - inicioMs)).coerceIn(0.0, 1.0)
        inicioChar + dentro * (fimChar - inicioChar)
    }
    val paragrafos = texto.paragrafos
    return ultimoAntes(paragrafos.size) { paragrafos[it].charOffset <= caractere }.coerceAtLeast(0)
}

/** Maior índice (de 0 a [tamanho] - 1) em que [antes] vale, numa lista em que vale até certo ponto; -1 se nenhum. */
private fun ultimoAntes(tamanho: Int, antes: (Int) -> Boolean): Int {
    var inicio = 0
    var fim = tamanho - 1
    var achado = -1
    while (inicio <= fim) {
        val meio = (inicio + fim) ushr 1
        if (antes(meio)) {
            achado = meio
            inicio = meio + 1
        } else {
            fim = meio - 1
        }
    }
    return achado
}
