package com.jonjonesbr.audiobookgen.tts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * "Mais tempo para frases curtas" (experimental, Supertonic). O Supertonic prevê a duração do trecho só pelo
 * texto e encaixa a fala nesse tempo; em frases curtas a previsão às vezes fica justa e o modelo engole palavras
 * ("para um", "está"). Aqui a frase curta é sintetizada mais devagar ([FATOR_VELOCIDADE]), o que dá mais tempo a
 * cada palavra, e o áudio é acelerado de volta ao ritmo pedido por [acelerar], sem mudar o tom da voz.
 */
object FolgaFrasesCurtas {
    /** Frases com até tantas letras ganham folga; nas longas a duração prevista costuma sobrar. */
    const val MAX_LETRAS = 50

    /** Abaixo disto ("Sim.", "— Não!") não há o que engolir. */
    private const val MIN_LETRAS = 4

    /** Velocidade de síntese relativa à pedida: 0,8 = 25% mais tempo para falar. */
    const val FATOR_VELOCIDADE = 0.8f

    private const val JANELA_MS = 30
    private const val TOLERANCIA_MS = 10

    fun aplica(texto: String): Boolean =
        texto.count { it.isLetterOrDigit() } in MIN_LETRAS..MAX_LETRAS

    /**
     * Acelera [audio] [fator] vezes (fator > 1 encurta) mantendo o tom: WSOLA, janelas de [JANELA_MS] com
     * sobreposição de metade, cada uma escolhida dentro de ±[TOLERANCIA_MS] para continuar a forma de onda da
     * anterior (sem isso a voz sai "borbulhando").
     */
    fun acelerar(audio: FloatArray, sampleRate: Int, fator: Float): FloatArray {
        val n = (sampleRate * JANELA_MS / 1000).let { it - it % 2 }
        if (fator <= 0f || kotlin.math.abs(fator - 1f) < 0.01f || audio.size < n * 2) return audio
        val passo = n / 2
        val tolerancia = sampleRate * TOLERANCIA_MS / 1000
        val janela = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val tamanhoSaida = (audio.size / fator).roundToInt()
        val saida = FloatArray(tamanhoSaida + n)
        val pesos = FloatArray(tamanhoSaida + n)

        var anterior = 0 // início, na entrada, do segmento usado na janela anterior
        var k = 0
        while (k * passo < tamanhoSaida) {
            val destino = k * passo
            val inicio = if (k == 0) {
                0
            } else {
                val nominal = (destino * fator).roundToInt()
                melhorInicio(audio, anterior + passo, nominal, tolerancia, n)
            }
            if (inicio + n > audio.size) {
                // Fim da entrada: o que sobra entra como está (com a janela), sem procurar encaixe.
                for (i in 0 until n) {
                    val origem = inicio + i
                    if (origem >= audio.size || destino + i >= saida.size) break
                    saida[destino + i] += audio[origem] * janela[i]
                    pesos[destino + i] += janela[i]
                }
                break
            }
            for (i in 0 until n) {
                saida[destino + i] += audio[inicio + i] * janela[i]
                pesos[destino + i] += janela[i]
            }
            anterior = inicio
            k++
        }
        return FloatArray(tamanhoSaida) { i -> if (pesos[i] > 1e-3f) saida[i] / pesos[i] else saida[i] }
    }

    /** Início perto de [nominal] cujo trecho mais se parece com a continuação natural ([natural]) do anterior. */
    private fun melhorInicio(audio: FloatArray, natural: Int, nominal: Int, tolerancia: Int, n: Int): Int {
        val limite = audio.size - n
        if (nominal > limite) return nominal.coerceAtMost(audio.size - 1).coerceAtLeast(0)
        if (natural + n > audio.size) return nominal.coerceIn(0, limite)
        var melhor = nominal.coerceIn(0, limite)
        var melhorValor = Double.NEGATIVE_INFINITY
        val de = (nominal - tolerancia).coerceAtLeast(0)
        val ate = (nominal + tolerancia).coerceAtMost(limite)
        // Correlação em metade da janela, candidatos e amostras de 2 em 2: barato e suficiente para o encaixe.
        val comparar = n / 2
        for (c in de..ate step 2) {
            var soma = 0.0
            var i = 0
            while (i < comparar) {
                soma += audio[natural + i].toDouble() * audio[c + i]
                i += 2
            }
            if (soma > melhorValor) {
                melhorValor = soma
                melhor = c
            }
        }
        return melhor
    }
}
