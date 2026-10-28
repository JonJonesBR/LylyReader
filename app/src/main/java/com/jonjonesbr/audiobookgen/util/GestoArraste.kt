package com.jonjonesbr.audiobookgen.util

import kotlin.math.abs
import kotlin.math.max

/** Eixo de um arraste, decidido quando o dedo sai da zona morta. DIAGONAL = nenhum dos dois: o gesto fica com a tela. */
enum class EixoArraste { HORIZONTAL, VERTICAL, DIAGONAL }

/** Para onde leva um arraste lateral. */
sealed class DestinoLateral {
    object Nenhum : DestinoLateral()
    data class AbaInterna(val indice: Int) : DestinoLateral()
    /** [ultimaAbaInterna]: voltando para trás, a tela abre na última aba interna (como numa sequência de páginas). */
    data class TelaPrincipal(val indice: Int, val ultimaAbaInterna: Boolean) : DestinoLateral()
}

/** Regras puras dos gestos de arraste (ver ui/ArrasteNaTela). */
object GestoArraste {

    /** O eixo dominante precisa ser este tanto maior que o outro; abaixo disso é diagonal (rolagem torta não vira troca de tela). */
    const val RAZAO_EIXO = 1.5f

    /** Eixo do arraste, ou null enquanto o dedo não saiu da zona morta [slop]. */
    fun eixo(dx: Float, dy: Float, slop: Float): EixoArraste? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (max(ax, ay) < slop) return null
        return when {
            ax >= ay * RAZAO_EIXO -> EixoArraste.HORIZONTAL
            ay >= ax * RAZAO_EIXO -> EixoArraste.VERTICAL
            else -> EixoArraste.DIAGONAL
        }
    }

    /**
     * Ao soltar: o arraste vale se percorreu [distanciaMin], ou metade disso com um movimento rápido
     * (velocidade de ao menos [velocidadeMin] no mesmo sentido do deslocamento).
     */
    fun completou(deslocamento: Float, velocidade: Float, distanciaMin: Float, velocidadeMin: Float): Boolean {
        val d = abs(deslocamento)
        if (d >= distanciaMin) return true
        val mesmoSentido = (deslocamento > 0f && velocidade > 0f) || (deslocamento < 0f && velocidade < 0f)
        return d >= distanciaMin / 2f && mesmoSentido && abs(velocidade) >= velocidadeMin
    }

    /**
     * Arraste lateral: primeiro passa pelas abas internas da tela; da última (ou da primeira, voltando)
     * segue para a tela principal vizinha. [paraFrente] = dedo da direita para a esquerda.
     * [abaInterna] / [totalAbasInternas]: aba selecionada e quantas há (0 = a tela não tem abas).
     */
    fun destinoLateral(
        paraFrente: Boolean,
        abaInterna: Int,
        totalAbasInternas: Int,
        telaPrincipal: Int,
        totalTelas: Int
    ): DestinoLateral {
        if (totalAbasInternas > 1 && abaInterna in 0 until totalAbasInternas) {
            val proxima = if (paraFrente) abaInterna + 1 else abaInterna - 1
            if (proxima in 0 until totalAbasInternas) return DestinoLateral.AbaInterna(proxima)
        }
        if (telaPrincipal !in 0 until totalTelas) return DestinoLateral.Nenhum
        val vizinha = if (paraFrente) telaPrincipal + 1 else telaPrincipal - 1
        if (vizinha !in 0 until totalTelas) return DestinoLateral.Nenhum
        return DestinoLateral.TelaPrincipal(vizinha, ultimaAbaInterna = !paraFrente)
    }
}
