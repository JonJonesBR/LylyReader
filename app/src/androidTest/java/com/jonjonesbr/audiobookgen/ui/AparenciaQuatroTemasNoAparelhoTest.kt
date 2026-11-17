package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.util.ThemePrefs
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_TECLADO_MS = 5_000L
private const val INTERVALO_TECLADO_MS = 50L
private const val POS_ARRAY_TAMANHO = 2

/**
 * Confere a Aparência nos 4 temas de leitura ([TemaLeitura]) e o comportamento de insets com
 * teclado nos 4 temas do app ([ThemePrefs]) no emulador.
 */
@RunWith(AndroidJUnit4::class)
class AparenciaQuatroTemasNoAparelhoTest {

    @get:Rule
    val regras = RegrasDoLeitor()

    @Test
    fun folhaAparenciaAlternaOsQuatroTemasSemErro() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)

        esperarAte(scenario, "leitor pronto com parágrafos") { a ->
            a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos).childCount > 0 &&
                a.findViewById<View>(R.id.btnReaderOpcoes).visibility == View.VISIBLE
        }

        onView(withId(R.id.btnReaderOpcoes)).perform(click())
        onView(withId(R.id.itemAcaoAparencia)).perform(click())
        onView(withId(R.id.btnTemaDoApp)).check(matches(isDisplayed()))

        val temas = listOf(
            R.id.btnTemaEscuro to TemaLeitura.ESCURO,
            R.id.btnTemaSepia to TemaLeitura.SEPIA,
            R.id.btnTemaOled to TemaLeitura.OLED,
            R.id.btnTemaClaro to TemaLeitura.CLARO
        )

        temas.forEach { (botaoId, tema) ->
            onView(withId(botaoId)).perform(click())
            val corEsperada = paletaDoLeitor(tema).fundo
            esperarAte(scenario, "fundo do leitor atualizado para $tema") { a ->
                val bg = a.findViewById<View>(R.id.readerRoot).background as? ColorDrawable
                bg?.color == corEsperada
            }
        }
    }

    @Test
    fun buscaComTecladoRespeitaInsetsNosQuatroTemasDoApp() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val contexto = instrumentacao.targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val modos = listOf(
            ThemePrefs.LIGHT,
            ThemePrefs.DARK,
            ThemePrefs.PAPEL,
            ThemePrefs.SYSTEM
        )

        try {
            modos.forEach { modo ->
                ThemePrefs.save(contexto, modo)
                instrumentacao.runOnMainSync { ThemePrefs.apply(modo) }

                val intent = Intent(contexto, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
                    .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
                val scenario = ActivityScenario.launch<ReaderActivity>(intent)
                try {
                    esperarAte(scenario, "botão de busca visível no modo $modo") { a ->
                        a.findViewById<View>(R.id.btnBuscarReader).visibility == View.VISIBLE
                    }

                    onView(withId(R.id.btnBuscarReader)).perform(click())
                    esperarAte(scenario, "campo de busca aberto no modo $modo") { a ->
                        a.findViewById<View>(R.id.layoutBuscaReader).visibility == View.VISIBLE &&
                            a.findViewById<View>(R.id.etBuscaReader).visibility == View.VISIBLE
                    }

                    onView(withId(R.id.etBuscaReader)).perform(click())
                    esperarTecladoSeAbrir(scenario, modo)

                    scenario.onActivity { a ->
                        val decor = a.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(decor)
                        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        val statusTop = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                        assertTrue("IME deveria estar aberto no modo $modo", imeBottom > 0)

                        val decorPos = IntArray(POS_ARRAY_TAMANHO).also { decor.getLocationOnScreen(it) }
                        val et = a.findViewById<View>(R.id.etBuscaReader)
                        val etPos = IntArray(POS_ARRAY_TAMANHO).also { et.getLocationOnScreen(it) }

                        assertTrue("etBuscaReader sem altura no modo $modo", et.height > 0)
                        assertTrue(
                            "etBuscaReader invadiu a barra de status no modo $modo",
                            etPos[1] >= decorPos[1] + statusTop
                        )
                        assertTrue(
                            "etBuscaReader coberto pelo teclado no modo $modo",
                            etPos[1] + et.height <= decorPos[1] + decor.height - imeBottom
                        )
                    }
                    runCatching { closeSoftKeyboard() }
                } finally {
                    runCatching { scenario.close() }
                }
            }
        } finally {
            ThemePrefs.save(contexto, ThemePrefs.SYSTEM)
            instrumentacao.runOnMainSync { ThemePrefs.apply(ThemePrefs.SYSTEM) }
        }
    }

    private fun esperarTecladoSeAbrir(scenario: ActivityScenario<ReaderActivity>, modo: Int) {
        val fim = System.currentTimeMillis() + TIMEOUT_TECLADO_MS
        while (System.currentTimeMillis() < fim) {
            var aberto = false
            scenario.onActivity { a ->
                val ime = ViewCompat.getRootWindowInsets(a.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())
                    ?.bottom ?: 0
                aberto = ime > 0
            }
            if (aberto) return
            Thread.sleep(INTERVALO_TECLADO_MS)
        }
        fail("Teclado virtual não abriu no modo $modo")
    }
}
