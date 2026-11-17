package com.jonjonesbr.audiobookgen.domain

import kotlin.math.max

/** Borda horizontal onde o botão flutuante ancora ao ser solto. */
enum class BordaLateral { ESQUERDA, DIREITA }

/** Margens internas ocupadas por barras do sistema e recortes de tela (em pixels). */
data class AreaSegura(
    val esquerda: Float = 0f,
    val topo: Float = 0f,
    val direita: Float = 0f,
    val fundo: Float = 0f,
)

/** Limites válidos (em pixels) para a coordenada (x, y) do canto superior esquerdo do botão. */
data class LimitesFlutuante(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

/**
 * Regras puras de posicionamento, arraste e efeito magnético do botão flutuante de conversão.
 */
object PosicaoFlutuante {

    const val FRACAO_VERTICAL_PADRAO = 0.72f
    private const val FRACAO_MEIO = 0.5f
    private const val METADE = 2f

    fun calcularLimites(
        larguraTela: Float,
        alturaTela: Float,
        tamanhoBotao: Float,
        margem: Float,
        areaSegura: AreaSegura = AreaSegura(),
    ): LimitesFlutuante {
        val minX = areaSegura.esquerda + margem
        val maxX = max(minX, larguraTela - areaSegura.direita - margem - tamanhoBotao)
        val minY = areaSegura.topo + margem
        val maxY = max(minY, alturaTela - areaSegura.fundo - margem - tamanhoBotao)
        return LimitesFlutuante(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
    }

    fun limitar(x: Float, y: Float, limites: LimitesFlutuante): Pair<Float, Float> {
        return x.coerceIn(limites.minX, limites.maxX) to y.coerceIn(limites.minY, limites.maxY)
    }

    /** Decide a borda magnética conforme o lado da tela em que o centro do botão foi solto. */
    fun bordaMagnetica(x: Float, larguraBotao: Float, larguraTela: Float): BordaLateral {
        val centroBotao = x + larguraBotao / METADE
        val centroTela = larguraTela / METADE
        return if (centroBotao < centroTela) BordaLateral.ESQUERDA else BordaLateral.DIREITA
    }

    fun xAncorado(borda: BordaLateral, limites: LimitesFlutuante): Float {
        return when (borda) {
            BordaLateral.ESQUERDA -> limites.minX
            BordaLateral.DIREITA -> limites.maxX
        }
    }

    fun fracaoVertical(y: Float, limites: LimitesFlutuante): Float {
        val faixa = limites.maxY - limites.minY
        if (faixa <= 0f) return FRACAO_MEIO
        return ((y - limites.minY) / faixa).coerceIn(0f, 1f)
    }

    fun yDeFracao(fracao: Float, limites: LimitesFlutuante): Float {
        val faixa = limites.maxY - limites.minY
        return limites.minY + faixa * fracao.coerceIn(0f, 1f)
    }

    fun ehArraste(dxTotal: Float, dyTotal: Float, touchSlop: Float): Boolean {
        return dxTotal * dxTotal + dyTotal * dyTotal >= touchSlop * touchSlop
    }
}
