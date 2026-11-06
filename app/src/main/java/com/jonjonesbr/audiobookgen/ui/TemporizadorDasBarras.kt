package com.jonjonesbr.audiobookgen.ui

import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.domain.RegraDasBarrasDoLeitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Recolhimento da barra de cima pelo tempo (rb-66, passo 3). Depois de [armar], conta o tempo sem interação
 * ([RegraDasBarrasDoLeitor.tempoParaRecolherMs], que respeita o "Tempo para agir" do sistema). Ao esgotar, se
 * [podeRecolher] diz que nenhum bloqueio vale, chama [aoEsgotar]; senão conta mais um período inteiro.
 *
 * Com a Activity parada (segundo plano, tela apagada), a contagem para e nada muda na tela. Ao voltar, [aoVoltar]
 * pede ao coordenador para reler e rearmar. Sem listener no AccessibilityManager: o TalkBack é relido pelo coordenador.
 */
internal class TemporizadorDasBarras(
    private val activity: AppCompatActivity,
    private val podeRecolher: () -> Boolean,
    private val aoEsgotar: () -> Unit,
    private val aoVoltar: () -> Unit,
) : DefaultLifecycleObserver {

    private var job: Job? = null

    // Verdadeiro quando o coordenador quer o tempo correndo. Interação só recomeça a contagem nesse caso.
    private var armado = false

    /** Começa a contar do zero. */
    fun armar() {
        armado = true
        contar()
    }

    /** Para a contagem e deixa de rearmar até o próximo [armar]. */
    fun cancelar() {
        armado = false
        job?.cancel()
        job = null
    }

    /** Interação nas barras: recomeça a contagem, se ela estiver armada. */
    fun tocou() {
        if (armado) contar()
    }

    private fun contar() {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            do {
                delay(RegraDasBarrasDoLeitor.tempoParaRecolherMs(tempoRecomendado()).toLong())
            } while (!podeRecolher())
            aoEsgotar()
        }
    }

    /** Tempo recomendado pelo sistema para quem lê na tela (API 29 em diante). Nulo antes disso. */
    private fun tempoRecomendado(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getSystemService(AccessibilityManager::class.java)?.getRecommendedTimeoutMillis(
                RegraDasBarrasDoLeitor.TEMPO_PADRAO_MS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_ICONS,
            )
        } else {
            null
        }

    override fun onStart(owner: LifecycleOwner) {
        aoVoltar()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Segundo plano: a contagem para e a tela parada não muda. Ao voltar, o coordenador rearma.
        job?.cancel()
        job = null
    }
}
