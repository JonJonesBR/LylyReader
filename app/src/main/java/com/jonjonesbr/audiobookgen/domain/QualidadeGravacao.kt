package com.jonjonesbr.audiobookgen.domain

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Avisos sobre uma gravação de referência para a clonagem de voz. */
enum class Aviso { CURTA, BAIXA, ESTOUROU, MUITO_SILENCIO, RUIDOSA }

/** Resultado da avaliação. [segundos] é a duração total do áudio analisado. */
data class Avaliacao(val avisos: List<Aviso>, val segundos: Float) {
    val boa: Boolean get() = avisos.isEmpty()
}

/** Faixa da duração da gravação guiada: abaixo de 5 s, de 5 a 10 s e 10 s ou mais. */
enum class Faixa { INSUFICIENTE, OK, OTIMO }

private const val QUADRO_MS = 50
/** Quadro com RMS acima disso conta como fala. */
private const val LIMIAR_VOZ = 0.002f
/** Quadro com RMS abaixo disso conta como silêncio. */
private const val LIMIAR_SILENCIO = 0.01f
private const val FALA_MINIMA_S = 7f
private const val RMS_FALA_BAIXA = 0.03f
private const val CLIPPING_MAXIMO = 0.01f
private const val SILENCIO_MAXIMO = 0.5f
private const val SNR_MINIMA_DB = 15.0
/** Abaixo disso a relação sinal/ruído não é medida (poucos quadros para os percentis). */
private const val SEGUNDOS_MINIMOS_PARA_RUIDO = 5f
private const val LIMIAR_CLIPPING = 32000

/** RMS normalizado (0..1) das amostras PCM16. Vazio devolve 0. */
fun rms(amostras: ShortArray): Float {
    if (amostras.isEmpty()) return 0f
    var soma = 0.0
    for (s in amostras) {
        val v = s / 32768.0
        soma += v * v
    }
    return sqrt(soma / amostras.size).toFloat()
}

/** Fração das amostras cujo valor absoluto chega ao [limiar] (saturação). */
fun proporcaoClipping(amostras: ShortArray, limiar: Int = LIMIAR_CLIPPING): Float {
    if (amostras.isEmpty()) return 0f
    return amostras.count { abs(it.toInt()) >= limiar }.toFloat() / amostras.size
}

/** Fração dos quadros de 50 ms com RMS abaixo de 0,01 (silêncio). Sem quadro completo, devolve 0. */
fun proporcaoSilencio(amostras: ShortArray, taxa: Int): Float {
    val quadros = rmsPorQuadro(amostras, taxa)
    if (quadros.isEmpty()) return 0f
    return quadros.count { it < LIMIAR_SILENCIO }.toFloat() / quadros.size
}

/** Avalia a gravação e devolve os avisos que o usuário deve ver antes de usar o áudio. */
fun avaliar(amostras: ShortArray, taxa: Int): Avaliacao {
    val segundos = amostras.size.toFloat() / taxa
    val quadros = rmsPorQuadro(amostras, taxa)
    val comFala = quadros.indices.filter { quadros[it] >= LIMIAR_VOZ }
    val segundosDeFala = comFala.size * QUADRO_MS / 1000f
    val avisos = mutableListOf<Aviso>()
    if (segundosDeFala < FALA_MINIMA_S) avisos += Aviso.CURTA
    if (comFala.isNotEmpty()) {
        val rmsFala = sqrt(comFala.map { quadros[it].toDouble() * quadros[it] }.average()).toFloat()
        if (rmsFala < RMS_FALA_BAIXA) avisos += Aviso.BAIXA
    }
    if (proporcaoClipping(amostras) > CLIPPING_MAXIMO) avisos += Aviso.ESTOUROU
    if (proporcaoSilencio(amostras, taxa) > SILENCIO_MAXIMO) avisos += Aviso.MUITO_SILENCIO
    if (segundos >= SEGUNDOS_MINIMOS_PARA_RUIDO && relacaoSinalRuidoDb(quadros) < SNR_MINIMA_DB) {
        avisos += Aviso.RUIDOSA
    }
    return Avaliacao(avisos, segundos)
}

/** Faixa para a barra de progresso: menos de 5 s, de 5 a menos de 10 s, ou 10 s ou mais. */
fun classificarProgresso(segundos: Float): Faixa = when {
    segundos < 5f -> Faixa.INSUFICIENTE
    segundos < 10f -> Faixa.OK
    else -> Faixa.OTIMO
}

private fun tamanhoQuadro(taxa: Int): Int = max(1, taxa * QUADRO_MS / 1000)

private fun rmsPorQuadro(amostras: ShortArray, taxa: Int): FloatArray {
    val tamanho = tamanhoQuadro(taxa)
    val total = amostras.size / tamanho
    return FloatArray(total) { q ->
        var soma = 0.0
        for (i in q * tamanho until (q + 1) * tamanho) {
            val v = amostras[i] / 32768.0
            soma += v * v
        }
        sqrt(soma / tamanho).toFloat()
    }
}

/** Percentil 90 (sinal) contra percentil 10 (ruído) dos quadros, em decibéis. Ruído zero conta como limpo. */
private fun relacaoSinalRuidoDb(quadros: FloatArray): Double {
    if (quadros.size < 2) return Double.POSITIVE_INFINITY
    val ordenados = quadros.sortedArray()
    val ruido = ordenados[(ordenados.size * 0.1).toInt()]
    val sinal = ordenados[(ordenados.size * 0.9).toInt().coerceAtMost(ordenados.lastIndex)]
    if (ruido <= 1e-6f) return Double.POSITIVE_INFINITY
    return 20 * log10(sinal / ruido.toDouble())
}
