package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * rb-26b: os chips do painel e os rótulos da velocidade cabem numa linha só no celular pequeno (360 dp). Infla o
 * layout, põe o estado da tela, mede com texto nativo e confere o `lineCount`. Sem Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutsDoLeitorTest {

    private companion object {
        const val LARGURA_TELA_DP = 360
        const val PASSOS_DO_MOTOR = 15
    }

    private val contexto: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.AppTheme,
    )

    /** Infla [layout], deixa [preparar] pôr o estado da tela e só então mede e posiciona numa largura de celular. */
    private fun medir(layout: Int, preparar: (View) -> Unit = {}): View {
        val pai = FrameLayout(contexto)
        val raiz = LayoutInflater.from(contexto).inflate(layout, pai, false)
        preparar(raiz)
        val larguraPx = (LARGURA_TELA_DP * contexto.resources.displayMetrics.density).toInt()
        raiz.measure(
            View.MeasureSpec.makeMeasureSpec(larguraPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        raiz.layout(0, 0, raiz.measuredWidth, raiz.measuredHeight)
        return raiz
    }

    @Test
    fun painelDoRetratoComVoltarNarracaoMantemOsChipsEmUmaLinha() {
        // Estado do modo páginas com o manual (ver ReaderTelaController.aplicarFabs): o botão no lugar do status.
        val painel = medir(R.layout.painel_guiada) { raiz ->
            raiz.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility = View.VISIBLE
            raiz.findViewById<View>(R.id.tvGuidedStatus).visibility = View.GONE
            raiz.findViewById<TextView>(R.id.tvGuidedPassos).apply {
                visibility = View.VISIBLE
                text = contexto.getString(R.string.guided_steps_chip, PASSOS_DO_MOTOR)
            }
            raiz.findViewById<TextView>(R.id.btnGuidedVelocidade).apply {
                visibility = View.VISIBLE
                text = "1×"
            }
        }

        val passos = painel.findViewById<TextView>(R.id.tvGuidedPassos)
        val velocidade = painel.findViewById<TextView>(R.id.btnGuidedVelocidade)
        val voltar = painel.findViewById<View>(R.id.btnGuidedVoltarNarracao)
        assertEquals("o chip de passos quebrou em linhas", 1, passos.lineCount)
        assertEquals("o chip de velocidade quebrou em linhas", 1, velocidade.lineCount)
        assertEquals(View.VISIBLE, velocidade.visibility)
        assertTrue("o chip de velocidade ficou sem largura", velocidade.width > 0)
        assertTrue("o botão de voltar ficou sem largura", voltar.width > 0)
    }

    @Test
    fun rotulosDaVelocidadeCabemNumaLinha() {
        val folha = medir(R.layout.bottom_sheet_velocidade)
        val ids = listOf(
            R.id.btnVelocidade075, R.id.btnVelocidade100, R.id.btnVelocidade125,
            R.id.btnVelocidade150, R.id.btnVelocidade175, R.id.btnVelocidade200,
        )
        for (id in ids) {
            val botao = folha.findViewById<TextView>(id)
            assertEquals("o rótulo '${botao.text}' quebrou em linhas", 1, botao.lineCount)
        }
    }
}
