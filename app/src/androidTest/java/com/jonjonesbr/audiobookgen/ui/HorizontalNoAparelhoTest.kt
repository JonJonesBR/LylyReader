package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarLinhaAbaixoDaBarra
import com.jonjonesbr.audiobookgen.ui.apoio.garantirPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leitor no modo horizontal no emulador (rb-26a, C12): cada parágrafo é uma página ([PaginaDoParagrafo]), e o parágrafo
 * longo rola por dentro dela. Um arraste vertical rola a página (e não a lista), e um toque longo numa palavra da
 * página rolada abre a seleção, com a palavra abaixo da barra. Os toques são reais (ver [Gestos]); as conferências
 * leem as Views na thread principal.
 */
@RunWith(AndroidJUnit4::class)
class HorizontalNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor. A preferência do modo horizontal é gravada
    // no corpo do teste (a regra apaga as preferências antes dele).
    @get:Rule
    val regras = RegrasDoLeitor()

    /**
     * C12 (rb-26a): no Leitor horizontal, o parágrafo 0 (longo) rola por dentro da página com um arraste vertical, e a
     * lista não troca de página. Depois, um toque longo numa palavra visível da página rolada abre a seleção (uma só,
     * como no C02), e a linha da palavra fica abaixo da barra da seleção.
     */
    @Test
    fun c12_paragrafoLongoRolaPorDentroNaHorizontal() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        try {
            val scenario = abrirNoHorizontal()
            regras.fecharDialogosDoSistema()
            scenario.onActivity { a ->
                val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
                assertEquals(
                    "O Leitor deveria estar na rolagem horizontal",
                    LinearLayoutManager.HORIZONTAL,
                    (lista.layoutManager as LinearLayoutManager).orientation,
                )
            }
            Capturas.capturar("60_c12_horizontal_inicio")

            // Arraste vertical sobre a página do parágrafo longo: a página rola por dentro, a lista fica na mesma página.
            val antes = medirRolagemDaPagina(scenario)
            rolarListaComODedo(scenario, FRACAO_DA_ROLAGEM, paraBaixoNoTexto = true, cenario = "c12")
            Thread.sleep(PAUSA_DEPOIS_DA_ROLAGEM_MS)
            regras.fecharDialogosDoSistema()
            val depois = medirRolagemDaPagina(scenario)
            Capturas.anotar(
                "c12",
                "rolagem por dentro: scrollY ${antes.scrollY} -> ${depois.scrollY}; " +
                    "primeiro visível ${antes.primeiroVisivel} -> ${depois.primeiroVisivel}; " +
                    "deslocamento horizontal ${antes.deslocamentoHorizontal} -> ${depois.deslocamentoHorizontal}",
            )
            Capturas.capturar("61_c12_rolou_por_dentro")
            assertTrue(
                "A página do parágrafo longo deveria rolar por dentro com o arraste vertical " +
                    "(scrollY ${antes.scrollY} -> ${depois.scrollY})",
                depois.scrollY > antes.scrollY,
            )
            assertEquals(
                "A lista não deveria trocar de página com o arraste vertical",
                antes.primeiroVisivel,
                depois.primeiroVisivel,
            )
            assertEquals(
                "A lista não deveria andar na horizontal com o arraste vertical",
                antes.deslocamentoHorizontal,
                depois.deslocamentoHorizontal,
            )

            // Toque longo numa palavra visível da página rolada (na metade de baixo, para a barra não cobrir).
            val palavra = escolherPalavraNaMetadeDeBaixo(scenario)
            Capturas.anotar("c12", "palavra escolhida na página rolada: '$palavra'")
            garantirPalavraVisivel(scenario, 0, palavra, "c12")
            regras.fecharDialogosDoSistema()
            selecionarPalavra(scenario, regras, 0, palavra, cenario = "c12")
            val selecao = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção depois do toque longo em '$palavra'" }
            regras.fecharDialogosDoSistema()
            Capturas.anotar("c12", "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}'")

            // A linha da palavra selecionada tem de ficar abaixo do fundo da barra (como no C02).
            val medida = esperarLinhaAbaixoDaBarra(scenario, 0)
            Capturas.anotar(
                "c12",
                "linha_topo=${medida?.topoDaLinha} barra_fundo=${medida?.fundoDaBarra} barra_rect=${medida?.rectBarra}",
            )
            Capturas.capturar("62_c12_selecao_na_pagina")
            assertTrue(
                "A seleção deveria conter '$palavra', veio '${selecao.texto}'",
                selecao.texto.contains(palavra),
            )
            assertTrue(
                "A linha da seleção deveria ficar abaixo da barra: linha_topo=${medida?.topoDaLinha} " +
                    "barra_fundo=${medida?.fundoDaBarra}",
                medida != null && medida.topoDaLinha >= medida.fundoDaBarra,
            )
            scenario.onActivity { a ->
                assertEquals(
                    "Deveria haver uma seleção aberta",
                    1,
                    a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos).selecoesAbertas,
                )
                assertEquals(
                    "A barra da seleção deveria estar visível",
                    View.VISIBLE,
                    a.findViewById<View>(R.id.barraSelecaoTexto).visibility,
                )
            }
        } finally {
            AppPrefs(contexto).readerRolagemHorizontal = false
        }
    }

    /**
     * Abre o livro de teste no Só ler com a rolagem horizontal já ligada na preferência. Espera a primeira página
     * (a PaginaDoParagrafo do parágrafo 0) aparecer na lista.
     */
    private fun abrirNoHorizontal(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache(TEXTOS)
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)
        esperarAte(scenario, "primeira página horizontal na tela") { a ->
            val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            lista.adapter?.itemCount == TEXTOS.size && paginaDo(lista, 0) != null
        }
        return scenario
    }

    /** Estado da página do parágrafo 0 e da lista, para comparar antes e depois do arraste. */
    private fun medirRolagemDaPagina(scenario: ActivityScenario<ReaderActivity>): MedidaDaPagina {
        var medida: MedidaDaPagina? = null
        scenario.onActivity { a ->
            val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            val pagina = checkNotNull(paginaDo(lista, 0)) { "O parágrafo 0 não tem página horizontal na tela" }
            medida = MedidaDaPagina(
                scrollY = pagina.scrollY,
                primeiroVisivel = (lista.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition(),
                deslocamentoHorizontal = lista.computeHorizontalScrollOffset(),
            )
        }
        return checkNotNull(medida) { "Sem medida da página horizontal" }
    }

    /**
     * Uma marca "marcoN" do parágrafo longo que está visível na página rolada, na metade de baixo da página, para o
     * toque longo não cair sob a barra da seleção. Lê na thread principal; falha se nenhuma servir.
     */
    private fun escolherPalavraNaMetadeDeBaixo(scenario: ActivityScenario<ReaderActivity>): String {
        var escolhida: String? = null
        scenario.onActivity { a ->
            val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            val pagina = checkNotNull(paginaDo(lista, 0)) { "O parágrafo 0 não tem página horizontal na tela" }
            val visivel = Rect()
            check(pagina.getGlobalVisibleRect(visivel)) { "A página do parágrafo 0 não está visível" }
            val folga = MARGEM_DA_BUSCA_DP * a.resources.displayMetrics.density
            val tv = pagina.texto
            escolhida = (1..TOTAL_DE_MARCAS).map { "marco$it" }.firstOrNull { palavra ->
                val ponto = Gestos.pontoDaPalavraNoTexto(tv, 0, palavra)
                ponto.y >= visivel.centerY() &&
                    ponto.y <= visivel.bottom - folga &&
                    ponto.x >= visivel.left &&
                    ponto.x <= visivel.right
            }
        }
        return checkNotNull(escolhida) { "Nenhuma palavra visível na metade de baixo da página rolada" }
    }

    /** A página (PaginaDoParagrafo) do parágrafo [indice], se ele está na lista agora. Na thread principal. */
    private fun paginaDo(lista: RecyclerView, indice: Int): PaginaDoParagrafo? =
        lista.findViewHolderForAdapterPosition(indice)?.itemView as? PaginaDoParagrafo

    /** Posição de rolagem da página e da lista, medida na thread principal. */
    private data class MedidaDaPagina(val scrollY: Int, val primeiroVisivel: Int, val deslocamentoHorizontal: Int)

    private companion object {
        /** Número de marcas "marcoN" no parágrafo longo (uma por frase), e a margem da busca da palavra, em dp. */
        const val TOTAL_DE_MARCAS = 50
        const val MARGEM_DA_BUSCA_DP = 48f

        /** Metade da área útil por arraste: a página do parágrafo longo tem muito mais que isso para rolar. */
        const val FRACAO_DA_ROLAGEM = 0.5f

        /** Pausa depois do arraste, para a página e a lista assentarem antes de medir. */
        const val PAUSA_DEPOIS_DA_ROLAGEM_MS = 400L

        /**
         * Parágrafo 0 longo (rola por dentro da página) e os demais dos textos de sempre. As frases do longo levam uma
         * marca única "marcoN," (N de 1 a 50) para a busca de palavra achar cada uma só uma vez.
         */
        val TEXTO_LONGO: String = "Capítulo um, parágrafo 1, longo. " +
            (1..TOTAL_DE_MARCAS).joinToString(" ") {
                "Frase $it, marco$it, do parágrafo que não cabe numa página só."
            }

        val TEXTOS: List<String> = LivroNoAparelho.PARAGRAFOS.toMutableList().apply { this[0] = TEXTO_LONGO }
    }
}
