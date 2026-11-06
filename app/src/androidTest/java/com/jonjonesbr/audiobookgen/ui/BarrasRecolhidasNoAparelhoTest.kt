package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Barras recolhidas no aparelho (rb-66, passo 4). No vertical, com a barra de cima em TEMPO, a setinha aparece no alto,
 * o toque mostra a barra, ela recolhe sozinha depois de uns segundos sem tocar, e rolar um pouco para cima com o dedo
 * a mostra de novo.
 *
 * R1: vertical, TEMPO. As capturas saem em `r1_*`, uma por estado.
 */
@RunWith(AndroidJUnit4::class)
class BarrasRecolhidasNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas (grava SEMPRE nas barras): ver RegrasDoLeitor. O modo TEMPO é
    // gravado no corpo do teste, depois dessa regra.
    @get:Rule
    val regras = RegrasDoLeitor()

    /** R1: vertical, modo TEMPO: a setinha aparece → toque mostra → sem tocar recolhe → rolar para cima mostra. */
    @Test
    fun r1_verticalTempoSetinhaRecolheERolagemMostra() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = false
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)

        // (1) Ao abrir: a barra de cima recolhida e a setinha no alto.
        esperarAte(scenario, "barra recolhida com a setinha no alto") { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.GONE &&
                a.findViewById<View>(R.id.btnMostrarBarras).visibility == View.VISIBLE
        }
        Capturas.capturar("r1_1_setinha")

        // (2) Toque na setinha: a barra aparece.
        val (x, y) = Gestos.centroDaView(scenario, R.id.btnMostrarBarras, "setinha de mostrar as barras")
        Gestos.toqueCurto(x.toFloat(), y.toFloat())
        esperarAte(scenario, "toque na setinha mostra a barra") { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.VISIBLE
        }
        Capturas.capturar("r1_2_mostrada")

        // (3) Sem tocar, a barra recolhe sozinha (o tempo do modo TEMPO é de uns 4 s).
        esperarAte(scenario, "barra recolhe sozinha sem interação", timeoutMs = 15_000L) { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.GONE
        }
        Capturas.capturar("r1_3_recolhida_pelo_tempo")

        // (4) Rolar para baixo com o dedo (a barra segue recolhida) e depois um pouco para cima: a barra volta.
        rolarListaComODedo(scenario, 0.3f, paraBaixoNoTexto = true, cenario = "r1_rolagem_para_baixo")
        esperarAte(scenario, "rolar para baixo mantém a barra recolhida") { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.GONE
        }
        rolarListaComODedo(scenario, 0.2f, paraBaixoNoTexto = false, cenario = "r1_rolagem_para_cima")
        esperarAte(scenario, "rolar um pouco para cima com o dedo mostra a barra") { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.VISIBLE
        }
        Capturas.capturar("r1_4_rolagem_para_cima")
        scenario.onActivity { a ->
            assertEquals(
                a.getString(R.string.cd_recolher_barras),
                a.findViewById<View>(R.id.btnMostrarBarras).contentDescription.toString(),
            )
        }
    }
}
