package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seleção de texto no "Só ler" no emulador (rb-46 U3): o toque longo seleciona uma palavra, a alça do fim amplia a
 * seleção, e a barra de ações cabe na tela com a fonte maior. Os toques são reais (ver [Gestos]); as conferências
 * leem as Views na thread principal. Se um cenário falhar por bug real, a falha fica como está: o código de produção
 * não muda aqui.
 */
@RunWith(AndroidJUnit4::class)
class SelecaoSoLerTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    /** Início, fim e texto de uma seleção no parágrafo 0. */
    private data class Selecao(val inicio: Int, val fim: Int, val texto: String)

    /** Topo da linha do início da seleção e fundo da barra, em coordenadas da tela (rb-48); o retângulo da barra é para as medidas. */
    private data class LinhaEBarra(val topoDaLinha: Int, val fundoDaBarra: Int, val rectBarra: String)

    /** Abre o livro de teste no Só ler, com [removidas] linhas tiradas pela limpeza (pílula da limpeza). */
    private fun abrirSoLer(removidas: Int = 0): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache(removidas = removidas)
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)
        esperarAte(scenario, "parágrafos na tela") { a ->
            val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            lista.adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size &&
                lista.findViewHolderForAdapterPosition(0) != null
        }
        return scenario
    }

    /**
     * Toque longo na palavra do parágrafo 0 e espera a barra da seleção aparecer com a seleção no TextView. Falha com a
     * mensagem da espera se a seleção não abrir.
     */
    private fun selecionarPalavra(scenario: ActivityScenario<ReaderActivity>, palavra: String) {
        val ponto = Gestos.pontoDaPalavra(scenario, 0, palavra)
        regras.fecharDialogosDoSistema()
        Gestos.toqueLongo(ponto.x, ponto.y)
        esperarAte(scenario, "barra da seleção aberta depois do toque longo em '$palavra'") { a ->
            a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.VISIBLE &&
                Gestos.textoDoParagrafo(a, 0).hasSelection()
        }
        // Só estabiliza: a descida da lista (rb-48) pode vir um quadro depois da barra. O resultado não importa aqui.
        esperarLinhaAbaixoDaBarra(scenario)
    }

    /**
     * Mede, na thread principal, a linha do início da seleção do parágrafo 0 e a base da barra, ambas em coordenadas
     * da tela. Nulo sem seleção ou sem layout. Mesma conta de linhaDoInicioDaSelecaoNaTela (rb-48).
     */
    private fun medirLinhaEBarra(scenario: ActivityScenario<ReaderActivity>): LinhaEBarra? {
        var medida: LinhaEBarra? = null
        scenario.onActivity { a ->
            val tv = Gestos.textoDoParagrafo(a, 0)
            val layout = tv.layout
            if (layout == null || !tv.hasSelection()) return@onActivity
            val inicio = minOf(tv.selectionStart, tv.selectionEnd)
            val posTv = IntArray(2)
            tv.getLocationOnScreen(posTv)
            val base = posTv[1] + tv.totalPaddingTop - tv.scrollY
            val topo = base + layout.getLineTop(layout.getLineForOffset(inicio))
            val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
            val posBarra = IntArray(2)
            barra.getLocationOnScreen(posBarra)
            val rectBarra = Rect()
            barra.getGlobalVisibleRect(rectBarra)
            medida = LinhaEBarra(topo, posBarra[1] + barra.height, rectBarra.toShortString())
        }
        return medida
    }

    /**
     * Lê a linha e a barra a cada [PAUSA_ENTRE_LEITURAS_MS] até a linha ficar abaixo da barra ou o tempo acabar. Não
     * falha: devolve a última medida, e quem chama decide o que fazer com ela.
     */
    private fun esperarLinhaAbaixoDaBarra(
        scenario: ActivityScenario<ReaderActivity>,
        timeoutMs: Long = 2_000L,
    ): LinhaEBarra? {
        val fim = System.currentTimeMillis() + timeoutMs
        var medida = medirLinhaEBarra(scenario)
        while ((medida == null || medida.topoDaLinha < medida.fundoDaBarra) && System.currentTimeMillis() < fim) {
            Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
            medida = medirLinhaEBarra(scenario)
        }
        return medida
    }

    /** Seleção atual do parágrafo 0, lida na thread principal; nulo se não há seleção. */
    private fun selecaoAtual(scenario: ActivityScenario<ReaderActivity>): Selecao? {
        var selecao: Selecao? = null
        scenario.onActivity { a ->
            val tv = Gestos.textoDoParagrafo(a, 0)
            if (tv.hasSelection()) {
                val inicio = tv.selectionStart
                val fim = tv.selectionEnd
                selecao = Selecao(inicio, fim, tv.text.subSequence(inicio, fim).toString())
            }
        }
        return selecao
    }

    /** Espera até 2 s a seleção do parágrafo 0 terminar depois de [fimAntes]; nulo se ela não crescer. */
    private fun esperarFimMaiorQue(scenario: ActivityScenario<ReaderActivity>, fimAntes: Int): Selecao? {
        val fim = System.currentTimeMillis() + TEMPO_PARA_SELECAO_MUDAR_MS
        var atual = selecaoAtual(scenario)
        while (atual == null || atual.fim <= fimAntes) {
            if (System.currentTimeMillis() >= fim) return null
            Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
            atual = selecaoAtual(scenario)
        }
        return atual
    }

    private fun visibilidade(a: ReaderActivity, id: Int): Int = a.findViewById<View>(id).visibility

    /** C02: só ler, o toque longo seleciona a palavra e a barra mostra só Destacar e Definir. */
    @Test
    fun c02_toqueLongoSelecionaPalavra() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, PALAVRA)
        val selecao = checkNotNull(selecaoAtual(scenario)) { "Sem seleção depois do toque longo em '$PALAVRA'" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c02", "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}'")
        // rb-48: a linha da palavra selecionada tem de ficar abaixo do fundo da barra (a lista desce se preciso).
        val medida = esperarLinhaAbaixoDaBarra(scenario)
        Capturas.anotar(
            "c02",
            "linha_topo=${medida?.topoDaLinha} barra_fundo=${medida?.fundoDaBarra} barra_rect=${medida?.rectBarra}"
        )
        Capturas.capturar("05_soler_selecao")
        assertTrue(
            "A linha da seleção deveria ficar abaixo da barra: linha_topo=${medida?.topoDaLinha} " +
                "barra_fundo=${medida?.fundoDaBarra}",
            medida != null && medida.topoDaLinha >= medida.fundoDaBarra
        )
        scenario.onActivity { a ->
            assertTrue(
                "A seleção deveria conter '$PALAVRA', veio '${selecao.texto}'",
                selecao.texto.contains(PALAVRA)
            )
            assertEquals(
                "A barra da seleção deveria estar visível",
                View.VISIBLE,
                visibilidade(a, R.id.barraSelecaoTexto)
            )
            BOTOES_DA_BARRA.forEach { (nome, id) ->
                val esperado = if (id in BOTOES_SO_LER) View.VISIBLE else View.GONE
                assertEquals("Botão '$nome' na barra do Só ler", esperado, visibilidade(a, id))
            }
        }
    }

    /**
     * C03: só ler, arrastar a alça do fim da seleção amplia a seleção até uma palavra mais adiante. Tenta a alça a
     * 12 dp (heurística), depois a 8 dp e a 16 dp abaixo da linha, e anota qual deu certo.
     */
    @Test
    fun c03_arrastarAlcaAmplia() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, PALAVRA)
        val antes = checkNotNull(selecaoAtual(scenario)) { "Sem seleção antes do arraste" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c03", "antes=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.capturar("06_soler_alca_antes")
        val alvo = Gestos.pontoDaPalavra(scenario, 0, PALAVRA_ADIANTE)

        var depois: Selecao? = null
        var abaixoQueDeu: Float? = null
        for (abaixoDp in DESLOCAMENTOS_DA_ALCA_DP) {
            if (selecaoAtual(scenario) == null) selecionarPalavra(scenario, PALAVRA)
            val alca = Gestos.pontoDaAlcaFinal(scenario, 0, abaixoDp)
            regras.fecharDialogosDoSistema()
            Gestos.arrastar(alca.x, alca.y, alvo.x, alvo.y)
            val achado = esperarFimMaiorQue(scenario, antes.fim)
            if (achado != null) {
                depois = achado
                abaixoQueDeu = abaixoDp
                break
            }
        }

        val resultado = depois
        val abaixo = abaixoQueDeu
        if (resultado == null || abaixo == null) {
            val mensagem = "Arrastar a alça não ampliou a seleção: fim antes=${antes.fim} texto='${antes.texto}', " +
                "alvo '$PALAVRA_ADIANTE' em (${alvo.x}, ${alvo.y}), última seleção=${selecaoAtual(scenario)}, " +
                "tentativas (dp abaixo da linha)=$DESLOCAMENTOS_DA_ALCA_DP"
            Capturas.anotar("c03", mensagem)
            Capturas.capturar("07_soler_alca_depois")
            throw AssertionError(mensagem)
        }
        Capturas.anotar(
            "c03",
            "alca_abaixo_dp=$abaixo depois=[${resultado.inicio},${resultado.fim}] texto='${resultado.texto}'"
        )
        Capturas.capturar("07_soler_alca_depois")
    }

    /**
     * C04: só ler com a fonte 1.3 e a pílula da limpeza (3 linhas removidas). A barra cabe na tela, sem corte nem
     * reticências, e a pílula não cobre a barra. Todas as medidas vão para medidas.txt; a falha lista cada problema.
     */
    @Test
    fun c04_barraCabeComFonteMaior() {
        regras.comandoDoShell("settings put system font_scale 1.3")
        val fonteAplicada = regras.comandoDoShell("settings get system font_scale").trim()
        val scenario = abrirSoLer(removidas = 3)
        // A pílula some quando a barra abre (BarraDaSelecaoDeTexto.mostrar): o retângulo dela antes da seleção vai
        // para as medidas, para mostrar onde ela ficava.
        var pilulaAntes = ""
        scenario.onActivity { a ->
            val pilula = a.findViewById<View>(R.id.bannerTextoOtimizado)
            val rPilula = Rect()
            pilula.getGlobalVisibleRect(rPilula)
            pilulaAntes = "pilula antes da seleção visivel=${pilula.visibility == View.VISIBLE} " +
                "rect=${rPilula.toShortString()}"
        }
        Capturas.anotar("c04", pilulaAntes)
        selecionarPalavra(scenario, PALAVRA)
        regras.fecharDialogosDoSistema()

        val medidas = mutableListOf<String>()
        val problemas = mutableListOf<String>()
        scenario.onActivity { a ->
            val largura = a.resources.displayMetrics.widthPixels
            medidas += "fonte=$fonteAplicada tela=${largura}px densidade=${a.resources.displayMetrics.density}"
            val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
            val rBarra = Rect()
            barra.getGlobalVisibleRect(rBarra)
            medidas += "barra=${rBarra.toShortString()} visivel=${barra.visibility == View.VISIBLE}"
            if (barra.visibility != View.VISIBLE) problemas += "a barra da seleção não está visível"

            BOTOES_DA_BARRA.forEach { (nome, id) ->
                val botao = a.findViewById<TextView>(id)
                if (botao.visibility != View.VISIBLE) return@forEach
                val rBotao = Rect()
                if (!botao.getGlobalVisibleRect(rBotao)) {
                    problemas += "'$nome' sem área visível na tela"
                } else {
                    medidas += "$nome rect=${rBotao.toShortString()} largura=${botao.width}px " +
                        "visivelNaTela=${rBotao.width()}px altura=${botao.height}px visivelNaAltura=${rBotao.height()}px"
                    if (rBotao.width() != botao.width) {
                        problemas += "'$nome' cortado: ${rBotao.width()} de ${botao.width}px visíveis"
                    }
                    if (rBotao.height() != botao.height) {
                        problemas += "'$nome' cortado na altura: ${rBotao.height()} de ${botao.height}px visíveis"
                    }
                    if (rBotao.left < 0 || rBotao.right > largura) {
                        problemas += "'$nome' fora da tela: ${rBotao.toShortString()} (tela ${largura}px)"
                    }
                }
                val layout = botao.layout
                if (layout == null) {
                    problemas += "'$nome' sem layout"
                } else {
                    val reticencias = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
                    medidas += "$nome linhas=${layout.lineCount} reticencias=$reticencias"
                    if (reticencias > 0) problemas += "'$nome' com reticências ($reticencias caracteres cortados)"
                }
            }

            val pilula = a.findViewById<View>(R.id.bannerTextoOtimizado)
            val rPilula = Rect()
            pilula.getGlobalVisibleRect(rPilula)
            val pilulaVisivel = pilula.visibility == View.VISIBLE
            medidas += "pilula visivel=$pilulaVisivel rect=${rPilula.toShortString()}"
            if (pilulaVisivel && Rect.intersects(rPilula, rBarra)) {
                problemas += "a pílula ${rPilula.toShortString()} cobre a barra ${rBarra.toShortString()}"
            }
        }
        medidas.forEach { Capturas.anotar("c04", it) }
        Capturas.capturar("08_soler_barra_fonte_grande")
        if (problemas.isNotEmpty()) {
            fail("Barra da seleção com fonte $fonteAplicada e pílula da limpeza:\n" + problemas.joinToString("\n"))
        }
    }

    private companion object {
        const val PALAVRA = "chuva"

        /** Palavra mais adiante no mesmo parágrafo, usada como alvo do arraste da alça. */
        const val PALAVRA_ADIANTE = "manhã"

        /** Distâncias abaixo da linha do fim (em dp) em que a alça é arrastada, na ordem das tentativas. */
        val DESLOCAMENTOS_DA_ALCA_DP = listOf(12f, 8f, 16f)

        /** Botões da barra da seleção, com o nome que aparece nas mensagens de falha. */
        val BOTOES_DA_BARRA = listOf(
            "Gerar áudio" to R.id.btnSelecaoTextoGerar,
            "Destacar" to R.id.btnSelecaoTextoDestacar,
            "Pronúncia" to R.id.btnSelecaoTextoPronuncia,
            "Vários parágrafos" to R.id.btnSelecaoTextoVarios,
            "Definir" to R.id.btnSelecaoTextoDefinir,
        )

        /** O que o Só ler mostra na barra: Gerar áudio, Pronúncia e Vários parágrafos ficam de fora. */
        val BOTOES_SO_LER = setOf(R.id.btnSelecaoTextoDestacar, R.id.btnSelecaoTextoDefinir)

        const val TEMPO_PARA_SELECAO_MUDAR_MS = 2_000L
        const val PAUSA_ENTRE_LEITURAS_MS = 100L
    }
}
