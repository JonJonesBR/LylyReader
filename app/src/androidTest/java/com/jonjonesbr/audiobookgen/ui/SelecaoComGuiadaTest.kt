package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.arrastarAlcaFinalAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.garantirPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seleção de texto com a leitura guiada no emulador (rb-46 U4, cenários C05 a C11). A voz é a longa (30 s por
 * trecho), então a guiada fica parada no parágrafo 0 enquanto o teste age. Os toques e os arrastes são reais (ver
 * [Gestos]); o estado da guiada vem de [GuidedPlaybackBridge]; as Views são lidas na thread principal. Antes de cada
 * toque num parágrafo, [garantirPalavraVisivel] confere que a palavra não está embaixo da barra da guiada (nem do
 * Snackbar) e arrasta a lista se preciso. As asserções são as do comportamento pedido: se um cenário falhar por bug
 * do app, a falha fica como está (a correção é item próprio).
 */
@RunWith(AndroidJUnit4::class)
class SelecaoComGuiadaTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    /** Estado atual da guiada (índice, tocando, carregando). Nulo se não há sessão. */
    private fun estadoDaGuiada() = GuidedPlaybackBridge.manager?.state?.value

    /** Espera a guiada tocar o parágrafo [indice]. */
    private fun esperarGuiadaTocando(scenario: ActivityScenario<ReaderActivity>, indice: Int) {
        esperarAte(scenario, "leitura guiada tocando o parágrafo $indice") { _ ->
            estadoDaGuiada()?.let { it.isPlaying && it.index == indice } == true
        }
    }

    /** Abre o Leitor já tocando o parágrafo 0 com a voz longa, e espera a guiada tocar. */
    private fun abrirTocando(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarGuiadaTocando(scenario, 0)
        regras.fecharDialogosDoSistema()
        return scenario
    }

    /** Pausa a guiada com um toque real no botão de tocar/pausar e espera a pausa. */
    private fun pausarPeloBotao(scenario: ActivityScenario<ReaderActivity>) {
        val (x, y) = Gestos.centroDaView(scenario, R.id.btnGuidedPlayPause, BOTAO_DE_TOCAR_PAUSAR)
        regras.fecharDialogosDoSistema()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(x, y)
        esperarAte(scenario, "leitura guiada pausada") { _ -> estadoDaGuiada()?.isPlaying == false }
    }

    /** Estado da guiada em uma linha, para as anotações e as mensagens de falha. */
    private fun descreverGuiada(estado: GuidedState?): String =
        "index=${estado?.index} isPlaying=${estado?.isPlaying} isLoading=${estado?.isLoading}"

    /** A view [id] está visível agora? Lida na thread principal. */
    private fun visivel(scenario: ActivityScenario<ReaderActivity>, id: Int): Boolean {
        var mostrando = false
        scenario.onActivity { a -> mostrando = a.findViewById<View>(id).visibility == View.VISIBLE }
        return mostrando
    }

    /** Texto atual da contagem da barra de vários parágrafos (tvSelecaoParagrafos), lido na thread principal. */
    private fun textoDaContagem(scenario: ActivityScenario<ReaderActivity>): String {
        var texto = ""
        scenario.onActivity { a -> texto = a.findViewById<TextView>(R.id.tvSelecaoParagrafos).text.toString() }
        return texto
    }

    /**
     * Espera a barra de vários parágrafos (layoutBarraSelecao) aparecer com a contagem [quantos], no texto que o app
     * põe em tvSelecaoParagrafos (o mesmo plural do app, para qualquer idioma).
     */
    private fun esperarContagem(scenario: ActivityScenario<ReaderActivity>, quantos: Int) {
        var esperado = ""
        scenario.onActivity { a ->
            esperado = a.resources.getQuantityString(R.plurals.barra_selecao_paragrafos, quantos, quantos)
        }
        esperarAte(scenario, "barra de vários parágrafos com '$esperado'") { a ->
            a.findViewById<View>(R.id.layoutBarraSelecao).visibility == View.VISIBLE &&
                a.findViewById<TextView>(R.id.tvSelecaoParagrafos).text.toString() == esperado
        }
    }

    /**
     * C05: com a guiada tocando no parágrafo 0, o toque longo numa palavra do parágrafo 1 seleciona a palavra, a
     * barra aparece, e a guiada continua tocando o mesmo índice depois de 1 s.
     */
    @Test
    fun c05_toqueLongoComGuiadaTocandoSeleciona() {
        val scenario = abrirTocando()
        val indiceAntes = estadoDaGuiada()?.index
        garantirPalavraVisivel(scenario, 1, PALAVRA_PARAGRAFO_1, "c05")
        selecionarPalavra(scenario, regras, 1, PALAVRA_PARAGRAFO_1)
        val selecao = checkNotNull(selecaoAtual(scenario, 1)) {
            "Sem seleção depois do toque longo em '$PALAVRA_PARAGRAFO_1' com a guiada tocando"
        }
        Thread.sleep(UM_SEGUNDO_MS)
        val depois = estadoDaGuiada()
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c05", "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}'")
        Capturas.anotar("c05", "guiada antes=$indiceAntes depois: ${descreverGuiada(depois)}")
        Capturas.capturar("10_guiada_selecao_tocando")
        assertTrue(
            "A seleção deveria conter '$PALAVRA_PARAGRAFO_1', veio '${selecao.texto}'",
            selecao.texto.contains(PALAVRA_PARAGRAFO_1)
        )
        assertTrue("A barra da seleção deveria estar visível", visivel(scenario, R.id.barraSelecaoTexto))
        assertEquals("A guiada deveria continuar no mesmo parágrafo depois da seleção", indiceAntes, depois?.index)
        assertTrue("A guiada deveria continuar tocando depois da seleção: $depois", depois?.isPlaying == true)
    }

    /**
     * C06: com a guiada tocando, arrastar a alça final da seleção do parágrafo 0 amplia a seleção, e a guiada
     * segue tocando o mesmo parágrafo.
     */
    @Test
    fun c06_arrastarAlcaComGuiadaTocandoAmplia() {
        val scenario = abrirTocando()
        val indiceAntes = estadoDaGuiada()?.index
        selecionarPalavra(scenario, regras, 0, PALAVRA_PARAGRAFO_0)
        val antes = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção antes do arraste" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c06", "antes=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.capturar("11_guiada_alca_antes")

        val resultado = arrastarAlcaFinalAte(
            scenario = scenario,
            indiceParagrafo = 0,
            palavraAlvo = PALAVRA_ADIANTE_PARAGRAFO_0,
            antes = antes,
            regras = regras,
            reselecionar = { selecionarPalavra(scenario, regras, 0, PALAVRA_PARAGRAFO_0) },
            anotar = { Capturas.anotar("c06", it) },
        )
        val ampliada = checkNotNull(resultado.depois) {
            "Arrastar a alça não ampliou a seleção com a guiada tocando: última seleção=" +
                "${selecaoAtual(scenario, 0)}, tentativas: ${resultado.tentativas.joinToString("; ")}"
        }
        Thread.sleep(UM_SEGUNDO_MS)
        val depois = estadoDaGuiada()
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c06", "ampliada=[${ampliada.inicio},${ampliada.fim}] texto='${ampliada.texto}'")
        Capturas.anotar("c06", "guiada antes=$indiceAntes depois: ${descreverGuiada(depois)}")
        Capturas.capturar("12_guiada_alca_depois")
        assertEquals("A guiada deveria continuar no mesmo parágrafo depois do arraste", indiceAntes, depois?.index)
        assertTrue("A guiada deveria continuar tocando depois do arraste: $depois", depois?.isPlaying == true)
    }

    /**
     * C07 (item 5 do usuário): com a guiada pausada, o toque longo numa palavra do parágrafo 1 (que não é o atual)
     * seleciona, o arraste da alça amplia, e a guiada continua pausada no mesmo índice: nenhum toque vira
     * "ler daqui".
     */
    @Test
    fun c07_pausadaSelecionaEArrastaSemTrocarDeParagrafo() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarGuiadaTocando(scenario, 0)
        regras.fecharDialogosDoSistema()
        pausarPeloBotao(scenario)
        val pausado = estadoDaGuiada()
        Capturas.anotar("c07", "pausada: index=${pausado?.index} isPlaying=${pausado?.isPlaying}")
        Capturas.capturar("13_guiada_pausada")

        garantirPalavraVisivel(scenario, 1, PALAVRA_PARAGRAFO_1, "c07")
        selecionarPalavra(scenario, regras, 1, PALAVRA_PARAGRAFO_1)
        val antes = checkNotNull(selecaoAtual(scenario, 1)) {
            "Sem seleção depois do toque longo em '$PALAVRA_PARAGRAFO_1' com a guiada pausada"
        }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c07", "selecao=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.capturar("14_guiada_pausada_selecao")

        val resultado = arrastarAlcaFinalAte(
            scenario = scenario,
            indiceParagrafo = 1,
            palavraAlvo = PALAVRA_ADIANTE_PARAGRAFO_1,
            antes = antes,
            regras = regras,
            reselecionar = { selecionarPalavra(scenario, regras, 1, PALAVRA_PARAGRAFO_1) },
            anotar = { Capturas.anotar("c07", it) },
        )
        val ampliada = checkNotNull(resultado.depois) {
            "Arrastar a alça não ampliou a seleção com a guiada pausada: última seleção=" +
                "${selecaoAtual(scenario, 1)}, tentativas: ${resultado.tentativas.joinToString("; ")}"
        }
        Thread.sleep(UM_SEGUNDO_MS)
        val depois = estadoDaGuiada()
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c07", "ampliada=[${ampliada.inicio},${ampliada.fim}] texto='${ampliada.texto}'")
        Capturas.anotar("c07", "depois: ${descreverGuiada(depois)}")
        Capturas.capturar("15_guiada_pausada_alca_depois")
        assertEquals(
            "A guiada pausada deveria continuar no parágrafo ${pausado?.index}, sem trocar",
            pausado?.index,
            depois?.index
        )
        assertTrue("A guiada deveria continuar pausada depois da seleção: $depois", depois?.isPlaying == false)
    }

    /**
     * C08 (rb-42): com a guiada tocando no parágrafo 0, um toque curto no meio do parágrafo 3 leva a guiada até ele
     * num toque só. A sequência de índices observada por 2,5 s tem de ser [0, 3] (uma só mudança), e o contador de
     * comandos de reprodução do manager tem de subir exatamente 1: um segundo disparo para o mesmo parágrafo não
     * muda o índice, mas aparece aí.
     */
    @Test
    fun c08_toqueCurtoVaiDiretoAoParagrafoNumToqueSo() {
        val scenario = abrirTocando()
        val indiceAntes = estadoDaGuiada()?.index ?: -1
        val alvo = garantirPalavraVisivel(scenario, 3, PALAVRA_PARAGRAFO_3, "c08")
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "c08",
            "toque curto em '$PALAVRA_PARAGRAFO_3' do parágrafo 3 (x=${alvo.x.toInt()} y=${alvo.y.toInt()})"
        )

        val comandosAntes = GuidedPlaybackBridge.manager?.comandosDeReproducao ?: -1L
        val sequencia = mutableListOf(indiceAntes)
        Gestos.toqueCurto(alvo.x, alvo.y)
        val fim = System.currentTimeMillis() + DOIS_E_MEIO_SEGUNDOS_MS
        while (System.currentTimeMillis() < fim) {
            Thread.sleep(AMOSTRA_MS)
            val indice = estadoDaGuiada()?.index ?: -1
            if (indice != sequencia.last()) sequencia += indice
        }
        val comandosDepois = GuidedPlaybackBridge.manager?.comandosDeReproducao ?: -1L
        val depois = estadoDaGuiada()
        val pedidosDoParagrafo3 = regras.vozFalsa.textosPedidos.count { it.contains(TRECHO_DO_PARAGRAFO_3) }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c08", "sequencia de índices=$sequencia pedidosDoParagrafo3=$pedidosDoParagrafo3")
        Capturas.anotar(
            "c08",
            "comandos de reprodução: antes=$comandosAntes depois=$comandosDepois " +
                "depois do toque: index=${depois?.index} isPlaying=${depois?.isPlaying}"
        )
        Capturas.capturar("16_guiada_toque_curto_no_3")
        assertEquals(
            "Um toque curto deveria dar um único play (rb-42), vieram ${comandosDepois - comandosAntes}",
            1L,
            comandosDepois - comandosAntes
        )
        assertEquals(
            "A guiada deveria ir direto ao parágrafo 3, com uma só mudança de índice",
            listOf(indiceAntes, 3),
            sequencia
        )
        assertTrue("A guiada deveria estar tocando o parágrafo 3: $depois", depois?.isPlaying == true)
    }

    /**
     * C09 (rb-42): com a guiada tocando no parágrafo 0, um toque longo no parágrafo 3 nunca vira "ler daqui". Com ou
     * sem seleção resultante (o registro diz qual), o índice da guiada continua 0.
     */
    @Test
    fun c09_toqueLongoNuncaLeDaqui() {
        val scenario = abrirTocando()
        val indiceAntes = estadoDaGuiada()?.index
        val ponto = garantirPalavraVisivel(scenario, 3, PALAVRA_PARAGRAFO_3, "c09")
        regras.fecharDialogosDoSistema()
        Gestos.toqueLongo(ponto.x, ponto.y)
        Thread.sleep(UM_SEGUNDO_MS)
        val selecao = selecaoAtual(scenario, 3)
        val depois = estadoDaGuiada()
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "c09",
            "selecao=${selecao?.let { "[${it.inicio},${it.fim}] '${it.texto}'" } ?: "nenhuma"} " +
                "guiada: index=${depois?.index} isPlaying=${depois?.isPlaying}"
        )
        Capturas.capturar("17_guiada_toque_longo_no_3")
        assertEquals("O toque longo no parágrafo 3 não deveria trocar a leitura guiada", indiceAntes, depois?.index)
    }

    /**
     * C10: com uma seleção aberta no parágrafo 2, um toque curto no parágrafo 4 só fecha a seleção (a barra
     * some e o parágrafo 2 não tem mais seleção), e a guiada não muda de índice.
     */
    @Test
    fun c10_comSelecaoAbertaToqueCurtoSoFecha() {
        val scenario = abrirTocando()
        val indiceAntes = estadoDaGuiada()?.index
        garantirPalavraVisivel(scenario, 2, PALAVRA_PARAGRAFO_2, "c10")
        selecionarPalavra(scenario, regras, 2, PALAVRA_PARAGRAFO_2)
        // Depois de abrir a seleção: a barra desce a lista (rb-48), então o ponto do 4 é medido só agora.
        val ponto = garantirPalavraVisivel(scenario, 4, PALAVRA_PARAGRAFO_4, "c10")
        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(ponto.x, ponto.y)
        esperarAte(scenario, "seleção fechada pelo toque curto no parágrafo 4") { a ->
            a.findViewById<View>(R.id.barraSelecaoTexto).visibility != View.VISIBLE &&
                !Gestos.textoDoParagrafo(a, 2).hasSelection()
        }
        Thread.sleep(UM_SEGUNDO_MS)
        val depois = estadoDaGuiada()
        val selecaoDepois = selecaoAtual(scenario, 2)
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c10", "toque no 4 em (x=${ponto.x.toInt()} y=${ponto.y.toInt()})")
        Capturas.anotar("c10", "selecao depois=${selecaoDepois ?: "nenhuma"}")
        Capturas.anotar("c10", "guiada antes=$indiceAntes depois: ${descreverGuiada(depois)}")
        Capturas.capturar("18_guiada_toque_fecha_selecao")
        assertTrue("A barra da seleção deveria sumir com o toque curto", !visivel(scenario, R.id.barraSelecaoTexto))
        assertNull("A seleção do parágrafo 2 deveria estar fechada", selecaoDepois)
        assertEquals("O toque que só fecha a seleção não deveria trocar a guiada", indiceAntes, depois?.index)
    }

    /**
     * C11: com a seleção aberta no parágrafo 2, "Vários parágrafos" (na barra da seleção) põe a contagem em 1 e
     * fecha o menu. Um toque curto no parágrafo 3 soma (contagem 2: a seleção vai do 2 ao 3; é o toque curto da
     * leitura guiada, com a seleção aberta, que estende a seleção). Cancelar esconde a barra, e depois disso um
     * toque curto no parágrafo 4 volta a ler dali (a seleção foi zerada, e a guiada vai para o 4).
     */
    @Test
    fun c11_varios_paragrafosContagemECancelar() {
        val scenario = abrirTocando()
        garantirPalavraVisivel(scenario, 2, PALAVRA_PARAGRAFO_2, "c11")
        selecionarPalavra(scenario, regras, 2, PALAVRA_PARAGRAFO_2)
        val (xVarios, yVarios) = Gestos.centroDaView(
            scenario,
            R.id.btnSelecaoTextoVarios,
            "botão Vários parágrafos da barra da seleção"
        )
        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(xVarios.toFloat(), yVarios.toFloat())
        esperarContagem(scenario, 1)
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "c11",
            "contagem depois de Vários: '${textoDaContagem(scenario)}' (toque em x=$xVarios y=$yVarios)"
        )
        Capturas.anotar("c11", "guiada: ${descreverGuiada(estadoDaGuiada())}")
        Capturas.capturar("19_guiada_varios_um")

        val ponto3 = garantirPalavraVisivel(scenario, 3, PALAVRA_PARAGRAFO_3, "c11")
        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(ponto3.x, ponto3.y)
        esperarContagem(scenario, 2)
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "c11",
            "contagem depois do toque no 3 (x=${ponto3.x.toInt()} y=${ponto3.y.toInt()}): " +
                "'${textoDaContagem(scenario)}'"
        )
        Capturas.anotar("c11", "guiada: ${descreverGuiada(estadoDaGuiada())}")
        Capturas.capturar("20_guiada_varios_dois")

        val (xCancelar, yCancelar) = Gestos.centroDaView(
            scenario,
            R.id.btnSelecaoCancelar,
            "botão Cancelar da barra de vários parágrafos"
        )
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "c11",
            "antes do Cancelar (x=$xCancelar y=$yCancelar): guiada ${descreverGuiada(estadoDaGuiada())}"
        )
        Gestos.toqueCurto(xCancelar.toFloat(), yCancelar.toFloat())
        esperarAte(scenario, "barra de vários parágrafos fechada pelo Cancelar") { a ->
            a.findViewById<View>(R.id.layoutBarraSelecao).visibility != View.VISIBLE
        }
        regras.fecharDialogosDoSistema()
        val barraDeVarios = visivel(scenario, R.id.layoutBarraSelecao)
        Capturas.anotar("c11", "depois do Cancelar: barra de vários visível=$barraDeVarios")
        Capturas.capturar("21_guiada_varios_cancelado")

        // Seleção zerada: um toque curto no parágrafo 4 volta a ler dali (sem seleção, o toque é o de sempre).
        val ponto4 = garantirPalavraVisivel(scenario, 4, PALAVRA_PARAGRAFO_4, "c11")
        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(ponto4.x, ponto4.y)
        esperarAte(scenario, "guiada no parágrafo 4 depois do Cancelar (a seleção não foi zerada)") { _ ->
            estadoDaGuiada()?.index == 4
        }
        val depois = estadoDaGuiada()
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c11", "depois do Cancelar e do toque no 4: ${descreverGuiada(depois)}")
        Capturas.capturar("22_guiada_cancelado_toque_no_4")
        assertEquals("Depois do Cancelar, o toque no parágrafo 4 deveria levar a guiada até ele", 4, depois?.index)
    }

    private companion object {
        /** Nome do botão de tocar/pausar nas mensagens de falha. */
        const val BOTAO_DE_TOCAR_PAUSAR = "botão de tocar/pausar da leitura guiada"

        /** Palavras dos parágrafos usados (índice 0 = "Capítulo um, parágrafo 1"; ver LivroNoAparelho). */
        const val PALAVRA_PARAGRAFO_0 = "chuva"
        const val PALAVRA_ADIANTE_PARAGRAFO_0 = "manhã"
        const val PALAVRA_PARAGRAFO_1 = "cozinha"
        const val PALAVRA_ADIANTE_PARAGRAFO_1 = "corredor"
        const val PALAVRA_PARAGRAFO_2 = "mercado"
        const val PALAVRA_PARAGRAFO_3 = "padre"
        const val PALAVRA_PARAGRAFO_4 = "farol"

        /**
         * Trecho que só existe no parágrafo 3 (índice 3, "parágrafo 4" do texto). Os parágrafos têm o mesmo
         * começo ("Capítulo um, parágrafo N."), então o começo não separa os pedidos à voz.
         */
        const val TRECHO_DO_PARAGRAFO_3 = "o padre passou pela escola"

        const val UM_SEGUNDO_MS = 1_000L
        const val DOIS_E_MEIO_SEGUNDOS_MS = 2_500L
        const val AMOSTRA_MS = 50L
    }
}
