package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.util.DivisorDeOracoes

/**
 * Regras puras da síntese Supertonic contra palavras e frases puladas.
 *
 * O Supertonic prevê UMA duração para o trecho inteiro e encaixa a fala nesse tempo. Quando a previsão sai
 * curta demais (mais comum em trechos longos), o modelo comprime a fala e pula palavras. Duas defesas:
 * trechos curtos por chamada ([dividir]) e, se o áudio ainda sair rápido demais para o texto, refazer o
 * trecho com mais tempo ([velocidadeParaRefazer]).
 */
object TrechosSupertonic {
    /** Antes eram 500 chars por chamada (o nativo recortava em ~300); trechos longos erram mais a duração. */
    const val MAX_CHARS = 160

    /** Letras por segundo (na velocidade 1.0) acima das quais a fala está comprimida demais. */
    const val LETRAS_POR_SEGUNDO_MAX = 18f

    /** Ritmo de leitura natural (letras por segundo na velocidade 1.0) usado ao refazer. */
    const val LETRAS_POR_SEGUNDO_ALVO = 14f

    /** Trechos curtos demais não dão medida confiável (o silêncio das bordas pesa). */
    private const val MIN_LETRAS = 20
    private const val VELOCIDADE_MIN_RELATIVA = 0.5f
    private val ESPACOS = Regex("\\s+")

    /** Divide [texto] em trechos de até [maxChars], por frase e, se preciso, por oração ou palavra. Não perde texto. */
    fun dividir(texto: String, maxChars: Int = MAX_CHARS): List<String> {
        val trechos = mutableListOf<String>()
        val atual = StringBuilder()
        for (oracao in DivisorDeOracoes.dividirEmOracoes(texto)) {
            for (parte in partir(oracao.texto, maxChars)) {
                if (atual.isNotEmpty() && atual.length + 1 + parte.length > maxChars) {
                    trechos += atual.toString()
                    atual.clear()
                }
                if (atual.isNotEmpty()) atual.append(' ')
                atual.append(parte)
            }
        }
        if (atual.isNotEmpty()) trechos += atual.toString()
        return trechos
    }

    /** Frase maior que o limite: corta depois de vírgula/ponto e vírgula/dois-pontos e, se não houver, entre palavras. */
    private fun partir(frase: String, maxChars: Int): List<String> {
        if (frase.length <= maxChars) return listOf(frase)
        val partes = mutableListOf<String>()
        val atual = StringBuilder()
        for (palavra in frase.split(ESPACOS).filter { it.isNotEmpty() }) {
            if (atual.isNotEmpty() && atual.length + 1 + palavra.length > maxChars) {
                partes += atual.toString()
                atual.clear()
            }
            if (atual.isNotEmpty()) atual.append(' ')
            atual.append(palavra)
            if (palavra.last() in ",;:" && atual.length >= maxChars / 2) {
                partes += atual.toString()
                atual.clear()
            }
        }
        if (atual.isNotEmpty()) partes += atual.toString()
        return partes
    }

    /** Letras (e dígitos) do texto: a medida de quanto há para falar. */
    fun letras(texto: String): Int = texto.count { it.isLetterOrDigit() }

    /** Letras por segundo na velocidade 1.0; null quando o trecho é curto demais para medir. */
    fun ritmo(texto: String, segundos: Float, velocidade: Float): Float? {
        val letras = letras(texto)
        if (segundos <= 0f || velocidade <= 0f || letras < MIN_LETRAS) return null
        return letras / segundos / velocidade
    }

    /**
     * Velocidade para refazer [texto] quando o áudio de [segundos] saiu rápido demais na [velocidade] pedida
     * (sinal de que o modelo pulou palavras); null quando o ritmo é plausível.
     *
     * Com [ritmoTipico] (o ritmo habitual desta voz, ver [RitmoDaVoz]), "rápido demais" é passar dele em
     * [FOLGA_SOBRE_O_TIPICO]: uma voz que fala 13 letras/s e de repente sai a 16 perdeu umas palavras, mesmo
     * abaixo do teto fixo. O refazer mira o ritmo típico (ou [LETRAS_POR_SEGUNDO_ALVO] sem ele).
     */
    fun velocidadeParaRefazer(
        texto: String,
        segundos: Float,
        velocidade: Float,
        ritmoTipico: Float? = null
    ): Float? {
        val ritmo = ritmo(texto, segundos, velocidade) ?: return null
        val limite = ritmoTipico?.let { (it * FOLGA_SOBRE_O_TIPICO).coerceAtMost(LETRAS_POR_SEGUNDO_MAX) }
            ?: LETRAS_POR_SEGUNDO_MAX
        val alvo = ritmoTipico ?: LETRAS_POR_SEGUNDO_ALVO
        return if (ritmo <= limite) {
            null
        } else {
            (velocidade * alvo / ritmo).coerceAtLeast(velocidade * VELOCIDADE_MIN_RELATIVA)
        }
    }

    /** O ritmo passa de 18% do habitual da voz: tido como palavras puladas. */
    const val FOLGA_SOBRE_O_TIPICO = 1.18f
}

/**
 * Ritmo habitual (letras/s na velocidade 1.0) de cada voz Supertonic nesta sessão: a mediana dos últimos
 * trechos aceitos. Começa a valer depois de [MINIMO_DE_AMOSTRAS] trechos; antes, vale o teto fixo.
 */
object RitmoDaVoz {
    private const val MINIMO_DE_AMOSTRAS = 5
    private const val MAXIMO_DE_AMOSTRAS = 40
    private val amostras = HashMap<String, ArrayDeque<Float>>()

    @Synchronized
    fun tipico(voz: String): Float? {
        val lista = amostras[voz] ?: return null
        return if (lista.size < MINIMO_DE_AMOSTRAS) null else mediana(lista)
    }

    /** Registra o ritmo de um trecho aceito (plausível). */
    @Synchronized
    fun registrar(voz: String, ritmo: Float) {
        val lista = amostras.getOrPut(voz) { ArrayDeque() }
        lista.addLast(ritmo)
        while (lista.size > MAXIMO_DE_AMOSTRAS) lista.removeFirst()
    }

    @Synchronized
    internal fun limpar() = amostras.clear()

    private fun mediana(valores: Collection<Float>): Float {
        val ordenados = valores.sorted()
        val meio = ordenados.size / 2
        return if (ordenados.size % 2 == 1) ordenados[meio] else (ordenados[meio - 1] + ordenados[meio]) / 2
    }
}
