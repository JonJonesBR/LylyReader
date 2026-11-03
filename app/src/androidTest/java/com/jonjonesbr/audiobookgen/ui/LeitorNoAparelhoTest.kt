package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Testes de interface do Leitor no emulador (rb-46), sem Robolectric: a ReaderActivity real, no processo do app, com o
 * livro posto no cache de texto e sem Python. Cada cenário tira capturas e medidas em [Capturas].
 */
@RunWith(AndroidJUnit4::class)
class LeitorNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    private lateinit var arquivo: File

    @Before
    fun setUp() {
        arquivo = LivroNoAparelho.gravarLivroNoCache()
    }

    /** C01: só ler abre o livro, com os parágrafos na tela. */
    @Test
    fun c01_abreNoSoLer() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        val scenario = regras.abrir(intent)
        esperarAte(scenario, "parágrafos na tela") { a ->
            lista(a).adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size && textoEm(lista(a), 0) != null
        }
        scenario.onActivity { a ->
            val rv = lista(a)
            assertEquals(LivroNoAparelho.PARAGRAFOS.size, rv.adapter?.itemCount)
            assertEquals(LivroNoAparelho.PARAGRAFOS[0], textoEm(rv, 0))
            assertEquals(View.VISIBLE, rv.visibility)
            assertEquals(LivroNoAparelho.NOME, a.findViewById<TextView>(R.id.tvReaderTitulo).text.toString())
            val metricas = a.resources.displayMetrics
            Capturas.anotar(
                "c01",
                "tela=${metricas.widthPixels}x${metricas.heightPixels}px " +
                    "densidade=${metricas.densityDpi}dpi " +
                    "largura=${metricas.widthPixels / metricas.density}dp " +
                    "itens=${rv.adapter?.itemCount}"
            )
        }
        regras.fecharDialogosDoSistema()
        Capturas.capturar("01_soler_aberto")
    }

    /** Texto do parágrafo na rolagem vertical, em que o item é o próprio TextView. */
    private fun textoEm(rv: RecyclerView, pos: Int): String? =
        (rv.findViewHolderForAdapterPosition(pos)?.itemView as? TextView)?.text?.toString()

    private fun lista(a: ReaderActivity): RecyclerView = a.findViewById(R.id.recyclerViewParagrafos)
}
