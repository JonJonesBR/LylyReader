package com.jonjonesbr.audiobookgen.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.TextView
import com.jonjonesbr.audiobookgen.util.EixoArraste
import com.jonjonesbr.audiobookgen.util.GestoArraste
import kotlin.math.abs
import kotlin.math.max

/**
 * Arraste lateral do modo páginas do Leitor (rb-26b), lido no dispatchTouchEvent da lista, no molde de [ArrasteNaTela].
 * O eixo é decidido no primeiro MOVE que passa da zona morta, antes de o RecyclerView ver esse MOVE: arraste lateral
 * completo chama [aoCompletar] (vira a página); qualquer outro gesto é engolido, e os filhos recebem ACTION_CANCEL.
 * Assim a lista não entra em arraste vertical no modo páginas. Seleção viva no toque, ou que nasce no meio dele, deixa
 * o gesto para o Editor.
 */
internal class ArrasteLateralDaPagina(
    private val lista: RecyclerViewParagrafos,
    private val podeVirar: () -> Boolean,
    private val aoCompletar: (paraFrente: Boolean) -> Unit,
) {
    // ENGOLIDO: o gesto já é da página (os filhos receberam o CANCEL) e o resto dele não chega a eles.
    // DIAGONAL_PENDENTE: começo torto dentro da folga. Os filhos já receberam o CANCEL, os MOVE são engolidos enquanto
    // o eixo ainda é medido: vira LATERAL (a página vira) ou ENGOLIDO.
    // DESCARTADO: o gesto segue normal para a lista e os filhos.
    private enum class Estado { OCIOSO, OBSERVANDO, DIAGONAL_PENDENTE, LATERAL, ENGOLIDO, DESCARTADO }

    private val densidade = lista.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(lista.context).scaledTouchSlop.toFloat()

    private var estado = Estado.OCIOSO
    private var lateralPossivel = false
    private var selecaoNoDown = false
    /** Parágrafo sob o dedo no DOWN, e se ele já tinha seleção ali (rb-26b). */
    private var tvNoDown: TextView? = null
    private var selecaoNoTvNoDown = false
    private var x0 = 0f
    private var y0 = 0f
    private var velocidade: VelocityTracker? = null
    private val posNaJanela = IntArray(2)

    /** Chamar de RecyclerViewParagrafos.dispatchTouchEvent, passando o super como [despachar]. */
    fun despachar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean =
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                iniciar(ev)
                despachar(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocidade?.addMovement(ev)
                mover(ev, despachar)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Dois dedos não são arraste: o gesto termina sem ação.
                if (estado == Estado.OBSERVANDO || estado == Estado.LATERAL || estado == Estado.DIAGONAL_PENDENTE) {
                    engolir(ev, despachar)
                }
                engolido() || despachar(ev)
            }
            MotionEvent.ACTION_UP -> {
                velocidade?.addMovement(ev)
                terminar(ev, despachar)
            }
            MotionEvent.ACTION_CANCEL -> {
                val jaEngolido = engolido()
                encerrar()
                jaEngolido || despachar(ev)
            }
            else -> engolido() || despachar(ev)
        }

    private fun iniciar(ev: MotionEvent) {
        encerrar()
        x0 = ev.x
        y0 = ev.y
        selecaoNoDown = selecaoViva(lista)
        // A seleção do toque longo nasce na palavra sob o dedo, antes do arraste e sem foco (o foco só vem no UP).
        tvNoDown = lista.findChildViewUnder(ev.x, ev.y)
            ?.let { (lista.getChildViewHolder(it) as? ParagrafoAdapter.VHLeitura)?.tv }
        selecaoNoTvNoDown = tvNoDown?.hasSelection() == true
        lista.getLocationInWindow(posNaJanela)
        lateralPossivel = podeVirar() && !selecaoNoDown &&
            foraDasBordasDoVoltar(lista.rootView, ev.x + posNaJanela[0])
        estado = if (podeVirar()) Estado.OBSERVANDO else Estado.DESCARTADO
        velocidade = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    /**
     * Em OBSERVANDO e em DIAGONAL_PENDENTE, decide o eixo. Depois, o MOVE só passa para a lista se o gesto não é mais
     * da página: o que está engolido não chega ao RecyclerView, que assim não entra em arraste vertical.
     */
    private fun mover(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean {
        if (estado == Estado.OBSERVANDO || estado == Estado.DIAGONAL_PENDENTE) observar(ev, despachar)
        return engolido() || despachar(ev)
    }

    private fun observar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean) {
        val dx = ev.x - x0
        val dy = ev.y - y0
        // Enquanto o dedo não sai da zona morta, o gesto ainda não tem dono: o MOVE segue para a lista.
        val eixo = GestoArraste.eixo(dx, dy, slop) ?: return
        val antiga = estado
        estado = when {
            // O Editor pediu o gesto (toque longo ou duplo toque, ver RecyclerViewParagrafos.editorDonoDoGesto):
            // é dele. Vem antes da folga diagonal.
            lista.editorDonoDoGesto -> Estado.DESCARTADO
            // Seleção que nasceu no toque (menu aberto ou texto com foco): a seleção é do Editor, que estende o trecho.
            selecaoViva(lista) && !selecaoNoDown -> Estado.DESCARTADO
            // Toque longo que acabou de selecionar a palavra sob o dedo, ainda sem foco nem menu: também é do Editor.
            tvNoDown?.hasSelection() == true && !selecaoNoTvNoDown -> Estado.DESCARTADO
            eixo == EixoArraste.HORIZONTAL && lateralPossivel -> Estado.LATERAL
            // Começo torto (arco do polegar) dentro da folga, como em ArrasteNaTela: o eixo segue sendo medido.
            eixo == EixoArraste.DIAGONAL && max(abs(dx), abs(dy)) < slop * FOLGA_DIAGONAL -> Estado.DIAGONAL_PENDENTE
            else -> Estado.ENGOLIDO
        }
        // O CANCEL vai aos filhos uma vez: na primeira decisão que tira o gesto deles. Dali em diante já estão sem ele.
        if (antiga == Estado.OBSERVANDO && engolido()) cancelar(ev, despachar)
    }

    private fun engolir(ev: MotionEvent, despachar: (MotionEvent) -> Boolean) {
        if (estado == Estado.OBSERVANDO) cancelar(ev, despachar)
        estado = Estado.ENGOLIDO
    }

    /** O CANCEL vai aos filhos que já receberam o DOWN, para o item sob o dedo não levar um clique. */
    private fun cancelar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean) {
        val cancelamento = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        despachar(cancelamento)
        cancelamento.recycle()
    }

    private fun terminar(ev: MotionEvent, despachar: (MotionEvent) -> Boolean): Boolean {
        val tratado = when (estado) {
            Estado.LATERAL -> {
                concluir(ev)
                true
            }
            Estado.ENGOLIDO, Estado.DIAGONAL_PENDENTE -> true
            else -> despachar(ev)
        }
        encerrar()
        return tratado
    }

    private fun concluir(ev: MotionEvent) {
        val vt = velocidade ?: return
        vt.computeCurrentVelocity(UNIDADE_VELOCIDADE_MS)
        val dx = ev.x - x0
        val distanciaMin = DISTANCIA_PARA_VIRAR_DP * densidade
        val velocidadeMin = VELOCIDADE_MIN_DP * densidade
        if (GestoArraste.completou(dx, vt.xVelocity, distanciaMin, velocidadeMin)) {
            aoCompletar(dx < 0f)
        }
    }

    private fun engolido(): Boolean =
        estado == Estado.LATERAL || estado == Estado.ENGOLIDO || estado == Estado.DIAGONAL_PENDENTE

    private fun encerrar() {
        estado = Estado.OCIOSO
        velocidade?.recycle()
        velocidade = null
        tvNoDown = null
    }

    companion object {
        /** Mesma folga de [ArrasteNaTela]: um começo diagonal curto ainda não decide o eixo. */
        private const val FOLGA_DIAGONAL = 3f
        private const val DISTANCIA_PARA_VIRAR_DP = 48f
        private const val VELOCIDADE_MIN_DP = 500f
        private const val UNIDADE_VELOCIDADE_MS = 1000
    }
}

/** Seleção viva da lista agora: menu aberto, ou texto selecionado com foco (rb-54). Função de topo (detekt). */
private fun selecaoViva(lista: RecyclerViewParagrafos): Boolean =
    lista.selecoesAbertas > 0 || lista.textoComSelecaoViva != null
