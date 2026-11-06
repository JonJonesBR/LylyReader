package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seleção que passa do corte no modo páginas (rb-67/3): a alça do fim levada até a linha escondida vira a página ao
 * soltar, e a ponta do fim fica à vista na página nova. O arraste é real (ver [Gestos]). A pausa de 300 ms no alvo
 * passa do filtro de toque do Editor (HandleView.filterOnTouchUp, como em [arrastarAlcaFinalAte]).
 */
@RunWith(AndroidJUnit4::class)
class SelecaoAlemDaPaginaNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor. A preferência do modo páginas é gravada no
    // corpo do teste, porque a regra apaga as preferências antes dele.
    @get:Rule
    val regras = RegrasDoLeitor()

    @Test
    fun alcaSoltaAlemDoCorteVirAPaginaESelecaoFicaAVista() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        try {
            // O parágrafo 0 é longo: a seleção começa na 1ª linha dele, e a alça vai até uma linha que sai da página.
            val textos = LivroNoAparelho.PARAGRAFOS.toMutableList().apply {
                this[0] = LivroNoAparelho.PARAGRAFO_LONGO
            }
            val arquivo = LivroNoAparelho.gravarLivroNoCache(textos)
            val intent = Intent(contexto, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
                .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
            val scenario = regras.abrir(intent)
            esperarAte(scenario, "modo páginas com o parágrafo 0 medido") { a ->
                listaDoLeitor(a).paginacao?.ativa == true && Gestos.textoDoParagrafoOuNulo(a, 0)?.layout != null
            }

            regras.fecharDialogosDoSistema()
            selecionarPalavra(scenario, regras, 0, "Capítulo", "rb67-3")
            val fimAntes = checkNotNull(selecaoAtual(scenario, 0)) { "sem seleção antes do arraste" }.fim
            var offsetAntes = 0
            var corte = 0
            var topoDaLista = 0
            scenario.onActivity { a ->
                val lista = listaDoLeitor(a)
                offsetAntes = lista.computeVerticalScrollOffset()
                corte = checkNotNull(lista.paginacao).corte()
                val posicao = IntArray(2)
                lista.getLocationOnScreen(posicao)
                topoDaLista = posicao[1]
            }

            // Arraste real da alça: o alvo é o meio da linha logo abaixo do corte (a linha escondida).
            val alca = Gestos.pontoDaAlcaFinal(scenario, 0)
            val fim = Gestos.pontoDoFimDaSelecao(scenario, 0)
            val alturaDaLinha = Gestos.alturaDaLinha(scenario, 0)
            val alvoY = topoDaLista + corte + alturaDaLinha / 2f
            regras.fecharDialogosDoSistema()
            Gestos.arrastar(
                alca.x,
                alca.y,
                alca.x,
                alca.y + (alvoY - fim.y) + FOLGA_DA_LINHA * alturaDaLinha,
                antesDeSoltar = { Thread.sleep(PAUSA_NO_ALVO_MS) },
            )

            // Ao soltar, a página vira para a ponta do fim, e a seleção continua viva.
            esperarAte(scenario, "a página virou para a ponta do fim") { a ->
                listaDoLeitor(a).computeVerticalScrollOffset() > offsetAntes
            }
            val depois = checkNotNull(selecaoAtual(scenario, 0)) { "a seleção sumiu depois da virada" }
            assertTrue("a seleção deveria ter crescido até a linha escondida", depois.fim > fimAntes)
            val fimNaTela = Gestos.pontoDoFimDaSelecao(scenario, 0)
            assertTrue(
                "a ponta do fim deveria estar na página nova: y ${fimNaTela.y}, corte ${topoDaLista + corte}",
                fimNaTela.y <= topoDaLista + corte,
            )
        } finally {
            AppPrefs(contexto).readerRolagemHorizontal = false
        }
    }

    private fun listaDoLeitor(a: ReaderActivity): RecyclerViewParagrafos = a.findViewById(R.id.recyclerViewParagrafos)

    private companion object {
        // Pausa com o dedo parado na linha de destino, antes do UP (ver ArrasteDaAlca.PAUSA_NO_ALVO_MS).
        const val PAUSA_NO_ALVO_MS = 300L

        // Folga extra do arraste, em fração da linha: sem ela a ponta do fim fica uma linha acima (ver ArrasteDaAlca).
        const val FOLGA_DA_LINHA = 0.8f
    }
}
