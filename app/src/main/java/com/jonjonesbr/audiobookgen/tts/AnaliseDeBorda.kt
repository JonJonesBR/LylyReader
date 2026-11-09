package com.jonjonesbr.audiobookgen.tts

import kotlin.math.sqrt

/**
 * Resultado da análise de borda de um trecho de áudio: quanto silêncio há no início e no fim, e se o começo tem
 * cara de sílaba repetida ("de-depois"): um vale de silêncio curto e fundo logo depois da primeira sílaba.
 */
data class ResultadoDeBorda(
    val inicioMs: Int,
    val fimMs: Int,
    val valesNoInicio: Int,
    val primeiroValeMs: Int?,
    val suspeitaDeInicioDuplicado: Boolean
)

/**
 * Análise pura (sem Android) da energia por quadros de 10 ms, usada só no diagnóstico do APK de teste.
 * Fala = quadros com RMS de pelo menos 10% do pico. Vale = trecho curto e fundo de silêncio dentro da janela
 * de 500 ms que começa na fala, entre dois quadros de energia alta.
 */
object AnaliseDeBorda {

    private const val QUADRO_MS = 10
    private const val MS_POR_SEGUNDO = 1000
    private const val LIMIAR_FALA = 0.10f
    private const val JANELA_MS = 500
    private const val JANELA_QUADROS = JANELA_MS / QUADRO_MS
    /** Vale: de 2 a 12 quadros (20 a 120 ms) abaixo de [VALE_MAX_FRACAO] da energia máxima da janela. */
    private const val VALE_MIN_QUADROS = 2
    private const val VALE_MAX_QUADROS = 12
    private const val VALE_MAX_FRACAO = 0.35f
    /** Quadro de energia alta, dos dois lados do vale. */
    private const val PICO_MIN_FRACAO = 0.60f
    /** Vale que começa entre estes instantes (depois do início da fala) e é fundo conta como começo duplicado. */
    private const val SUSPEITA_MIN_MS = 60
    private const val SUSPEITA_MAX_MS = 260
    private const val SUSPEITA_PROFUNDIDADE = 0.25f

    private val ZERO = ResultadoDeBorda(0, 0, 0, null, false)

    /** Um vale: [inicioQuadro] (índice do array de quadros), [duracao] em quadros e [profundidade] relativa ao pico. */
    private data class Vale(val inicioQuadro: Int, val duracao: Int, val profundidade: Float)

    /** [audio] em amostras mono (-1..1) à taxa [taxa]. Áudio vazio ou mudo devolve tudo zerado. */
    fun analisar(audio: FloatArray, taxa: Int): ResultadoDeBorda {
        val rms = rmsPorQuadro(audio, taxa)
        val pico = rms.maxOrNull()?.takeIf { it > 0f } ?: return ZERO
        val limiar = pico * LIMIAR_FALA
        val onset = rms.indexOfFirst { it >= limiar }
        val ultimo = rms.indexOfLast { it >= limiar }
        val vales = valesNaJanela(rms, onset)
        return ResultadoDeBorda(
            inicioMs = onset * QUADRO_MS,
            fimMs = (rms.size - 1 - ultimo) * QUADRO_MS,
            valesNoInicio = vales.size,
            primeiroValeMs = vales.firstOrNull()?.let { deslocamentoMs(it, onset) },
            suspeitaDeInicioDuplicado = vales.any {
                deslocamentoMs(it, onset) in SUSPEITA_MIN_MS..SUSPEITA_MAX_MS &&
                    it.profundidade < SUSPEITA_PROFUNDIDADE
            }
        )
    }

    /** RMS de cada quadro de 10 ms. O último quadro incompleto é descartado. */
    private fun rmsPorQuadro(audio: FloatArray, taxa: Int): FloatArray {
        val tamanho = taxa * QUADRO_MS / MS_POR_SEGUNDO
        if (tamanho <= 0) return FloatArray(0)
        return FloatArray(audio.size / tamanho) { q ->
            var soma = 0.0
            for (k in q * tamanho until (q + 1) * tamanho) soma += audio[k] * audio[k]
            sqrt(soma / tamanho).toFloat()
        }
    }

    /** Vales na janela de [JANELA_QUADROS] quadros que começa no [onset]. */
    private fun valesNaJanela(rms: FloatArray, onset: Int): List<Vale> {
        val fim = minOf(onset + JANELA_QUADROS, rms.lastIndex)
        val janelaMax = (onset..fim).maxOf { rms[it] }
        val baixo = janelaMax * VALE_MAX_FRACAO
        val alto = janelaMax * PICO_MIN_FRACAO
        val vales = mutableListOf<Vale>()
        var i = onset + 1
        while (i <= fim) {
            if (rms[i] < baixo && rms[i - 1] >= alto) {
                val inicio = i
                while (i <= fim && rms[i] < baixo) i++
                val duracao = i - inicio
                if (duracao in VALE_MIN_QUADROS..VALE_MAX_QUADROS && i <= fim && rms[i] >= alto) {
                    val menor = (inicio until i).minOf { rms[it] }
                    vales += Vale(inicio, duracao, menor / janelaMax)
                }
            } else {
                i++
            }
        }
        return vales
    }

    private fun deslocamentoMs(vale: Vale, onset: Int): Int = (vale.inicioQuadro - onset) * QUADRO_MS
}
