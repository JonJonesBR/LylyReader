package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AbsSeekBar
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.jonjonesbr.audiobookgen.util.EixoArraste
import com.jonjonesbr.audiobookgen.util.GestoArraste
import kotlin.math.abs
import kotlin.math.max

/** Views que, visíveis sob o dedo, desligam os gestos da tela (ex.: a dica sobreposta do CoachMark). */
interface BloqueiaArrasteDeTela

/**
 * Gestos de arraste da tela inteira, lidos em dispatchTouchEvent (antes dos filhos):
 * - lateral: [aoArrastarLateral] (troca a aba interna ou a tela principal);
 * - vertical sobre o mini player: [aoArrastarMiniPlayer] (para cima abre, para baixo recolhe).
 * Reconhecido o gesto, os filhos recebem ACTION_CANCEL (o item sob o dedo não leva um clique acidental)
 * e o resto do gesto não chega a eles. Não começa sobre controles que já se arrastam de lado
 * (barras de progresso, sliders, chaves, campos de texto, listas e faixas que rolam na horizontal),
 * nem nas bordas, que são o "voltar" do sistema.
 */
class ArrasteNaTela(
    private val activity: Activity,
    private val lateralLigado: () -> Boolean,
    private val aoArrastarLateral: (paraFrente: Boolean) -> Unit,
    private val sobreMiniPlayer: (x: Float, y: Float) -> Boolean,
    private val aoArrastarMiniPlayer: (paraCima: Boolean) -> Unit
) {
    // ANULADO: gesto já tirado dos filhos que terminou sem ação (entrou um segundo dedo); o resto dele é engolido.
    private enum class Estado { OCIOSO, OBSERVANDO, LATERAL, MINI_PLAYER, ANULADO, DESCARTADO }

    private val densidade = activity.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()

    private var estado = Estado.OCIOSO
    private var lateralPossivel = false
    private var miniPossivel = false
    private var x0 = 0f
    private var y0 = 0f
    private var velocidade: VelocityTracker? = null

    /** Chamar de Activity.dispatchTouchEvent, passando o super como [despachar]. */
    fun despachar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                iniciar(ev)
                return despachar(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Dois dedos não são arraste: um gesto já reconhecido termina sem ação.
                if (estado == Estado.OBSERVANDO) estado = Estado.DESCARTADO
                if (reconhecido()) estado = Estado.ANULADO
            }
            MotionEvent.ACTION_MOVE -> {
                velocidade?.addMovement(ev)
                if (estado == Estado.OBSERVANDO) observar(ev, despachar)
            }
            MotionEvent.ACTION_UP -> {
                velocidade?.addMovement(ev)
                if (reconhecido()) {
                    concluir(ev)
                    encerrar()
                    return true
                }
                encerrar()
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumir = reconhecido()
                encerrar()
                if (consumir) return true
            }
        }
        return if (reconhecido()) true else despachar(ev)
    }

    private fun reconhecido() = estado == Estado.LATERAL || estado == Estado.MINI_PLAYER || estado == Estado.ANULADO

    private fun iniciar(ev: MotionEvent) {
        encerrar()
        x0 = ev.x
        y0 = ev.y
        val raiz = activity.window?.decorView
        // Primeiro o que é barato; a varredura das views sob o dedo só quando algum gesto é possível.
        miniPossivel = raiz != null && sobreMiniPlayer(ev.x, ev.y)
        lateralPossivel = raiz != null && !miniPossivel && lateralLigado() && foraDasBordas(raiz, ev.x)
        if ((lateralPossivel || miniPossivel) && sobreBloqueio(raiz!!, ev.x, ev.y)) {
            lateralPossivel = false
            miniPossivel = false
        }
        if (!lateralPossivel && !miniPossivel) {
            estado = Estado.DESCARTADO
            return
        }
        estado = Estado.OBSERVANDO
        velocidade = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun observar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean) {
        val dx = ev.x - x0
        val dy = ev.y - y0
        val eixo = GestoArraste.eixo(dx, dy, slop) ?: return
        // Começo torto (arco do polegar): continua observando até o eixo se definir, por um trecho curto.
        if (eixo == EixoArraste.DIAGONAL && max(abs(dx), abs(dy)) < slop * FOLGA_DIAGONAL) return
        estado = when {
            eixo == EixoArraste.HORIZONTAL && lateralPossivel -> Estado.LATERAL
            eixo == EixoArraste.VERTICAL && miniPossivel -> Estado.MINI_PLAYER
            else -> Estado.DESCARTADO
        }
        if (reconhecido()) {
            val cancelar = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            despachar(cancelar)
            cancelar.recycle()
        }
    }

    private fun concluir(ev: MotionEvent) {
        val vt = velocidade ?: return
        vt.computeCurrentVelocity(1000)
        val velocidadeMin = VELOCIDADE_MIN_DP * densidade
        when (estado) {
            Estado.LATERAL -> {
                val dx = ev.x - x0
                if (GestoArraste.completou(dx, vt.xVelocity, DISTANCIA_LATERAL_DP * densidade, velocidadeMin)) {
                    aoArrastarLateral(dx < 0f)
                }
            }
            Estado.MINI_PLAYER -> {
                val dy = ev.y - y0
                if (GestoArraste.completou(dy, vt.yVelocity, DISTANCIA_MINI_DP * densidade, velocidadeMin)) {
                    aoArrastarMiniPlayer(dy < 0f)
                }
            }
            else -> Unit
        }
    }

    private fun encerrar() {
        estado = Estado.OCIOSO
        velocidade?.recycle()
        velocidade = null
    }

    /** As bordas laterais ficam para o gesto de voltar do sistema (no mínimo [BORDA_DP]). */
    private fun foraDasBordas(raiz: View, x: Float): Boolean {
        val gestos = ViewCompat.getRootWindowInsets(raiz)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val minimo = BORDA_DP * densidade
        val esquerda = max(minimo, gestos?.left?.toFloat() ?: 0f)
        val direita = max(minimo, gestos?.right?.toFloat() ?: 0f)
        return x > esquerda && x < raiz.width - direita
    }

    /** Percorre só os ramos sob o dedo: true se algum deles já usa o arraste lateral ou bloqueia os gestos. */
    private fun sobreBloqueio(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE || !contem(view, x, y)) return false
        if (view is BloqueiaArrasteDeTela || controleDeArrasteLateral(view)) return true
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                if (sobreBloqueio(view.getChildAt(i), x, y)) return true
            }
        }
        return false
    }

    private fun controleDeArrasteLateral(view: View): Boolean =
        view is AbsSeekBar || view is Slider || view is RangeSlider || view is EditText ||
            // Chaves liga/desliga: o polegar delas também se arrasta de lado.
            view is SwitchCompat || view is android.widget.Switch ||
            // Só contêineres: um TextView de uma linha com texto longo "rola" na horizontal e travaria o gesto à toa.
            ((view is ViewGroup || view is WebView) &&
                (view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)))

    private val local = IntArray(2)

    private fun contem(view: View, x: Float, y: Float): Boolean {
        view.getLocationInWindow(local)
        return x >= local[0] && x < local[0] + view.width && y >= local[1] && y < local[1] + view.height
    }

    companion object {
        private const val BORDA_DP = 24f
        private const val FOLGA_DIAGONAL = 3f
        private const val DISTANCIA_LATERAL_DP = 80f
        private const val DISTANCIA_MINI_DP = 40f
        private const val VELOCIDADE_MIN_DP = 500f
    }
}
