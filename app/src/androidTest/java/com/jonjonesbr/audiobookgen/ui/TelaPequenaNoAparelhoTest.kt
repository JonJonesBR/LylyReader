package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.TEMPO_LIMITE_ESPERA_MS
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.fundoParaEscolherPalavra
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import com.jonjonesbr.audiobookgen.util.dpToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sonda de tela pequena (rb-26b, tela pequena, passo 0): quantas linhas inteiras do modo páginas cabem na tela com a
 * fonte 1,3, no retrato e na paisagem, com a leitura guiada, com a seleção e no Só ler. É linha de base: os testes não
 * conferem o número de linhas, só que a tela abriu, que a medida foi feita e que a captura saiu. As medidas vão para
 * medidas.txt com o prefixo [sN] de cada cenário.
 *
 * S1: retrato com a guiada tocando. S2: retrato com a guiada em modo manual e uma seleção. S3: paisagem com a guiada.
 * S4: paisagem com a guiada e uma seleção. S5: paisagem no Só ler. Todos no modo páginas.
 */
@RunWith(AndroidJUnit4::class)
class TelaPequenaNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor. A preferência do modo páginas é gravada no
    // corpo de cada teste (ver [comPaginas]), porque a regra apaga as preferências antes dele.
    @get:Rule
    val regras = RegrasDoLeitor()

    private val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /** S1: retrato, leitura guiada tocando (voz falsa longa). */
    @Test
    fun s1_retratoComGuiadaTocando() {
        comPaginas {
            comFonteGrande {
                val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                regras.usarVoz(VozFalsa.longa(contexto))
                val scenario = abrirLeitorComGuiada(regras, contexto)
                esperarGuiadaTocando(scenario, 0)
                esperarPaginaComLinhas(scenario)
                esperarPaginaParada(scenario)
                registrar(scenario, "s1", "s1_retrato_guiada", "retrato, guiada tocando")
            }
        }
    }

    /** S2: retrato, guiada virada em modo manual com um arraste lateral, e um toque longo numa palavra visível. */
    @Test
    fun s2_retratoComGuiadaESelecao() {
        comPaginas {
            comFonteGrande {
                val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                regras.usarVoz(VozFalsa.longa(contexto))
                val scenario = abrirLeitorComGuiada(regras, contexto)
                virarPaginaComGuiadaEmManual(scenario, indice = 0)
                val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
                selecionarPalavra(scenario, regras, indice, palavra, cenario = "s2")
                registrar(scenario, "s2", "s2_retrato_guiada_selecao", "retrato, guiada e seleção")
            }
        }
    }

    /** S3: paisagem (rotação manual antes de abrir), leitura guiada tocando. */
    @Test
    fun s3_paisagemComGuiadaTocando() {
        comPaginas {
            comFonteGrande {
                comRotacaoManual {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    girarParaPaisagem(scenario, comGuiada = true)
                    esperarGuiadaTocando(scenario, indice = null)
                    esperarPaginaComLinhas(scenario)
                    esperarPaginaParada(scenario)
                    registrar(scenario, "s3", "s3_paisagem_guiada", "paisagem, guiada tocando")
                }
            }
        }
    }

    /** S4: paisagem, guiada em modo manual e uma seleção. */
    @Test
    fun s4_paisagemComGuiadaESelecao() {
        comPaginas {
            comFonteGrande {
                comRotacaoManual {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    girarParaPaisagem(scenario, comGuiada = true)
                    virarPaginaComGuiadaEmManual(scenario, indice = null)
                    val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
                    selecionarPalavra(scenario, regras, indice, palavra, cenario = "s4")
                    registrar(scenario, "s4", "s4_paisagem_guiada_selecao", "paisagem, guiada e seleção")
                }
            }
        }
    }

    /** S5: paisagem no Só ler, sem a leitura guiada. */
    @Test
    fun s5_paisagemSoLer() {
        comPaginas {
            comFonteGrande {
                comRotacaoManual {
                    val scenario = abrirSoLerPaginado()
                    girarParaPaisagem(scenario, comGuiada = false)
                    esperarPaginaComLinhas(scenario)
                    esperarPaginaParada(scenario)
                    // Sem a guiada, o painel dela (oculto no XML) não pode aparecer, senão a medida muda de sentido.
                    var painelVisivel = true
                    scenario.onActivity { a ->
                        painelVisivel = a.findViewById<View>(R.id.layoutGuidedPlayer).visibility == View.VISIBLE
                    }
                    assertFalse("No Só ler o painel da leitura guiada deveria estar oculto", painelVisivel)
                    registrar(scenario, "s5", "s5_paisagem_so_ler", "paisagem, só ler")
                }
            }
        }
    }

    /**
     * P13 (rb-26b, passo 6), paisagem com a fonte 1,3: depois de virar uma página com a guiada em modo manual, o
     * cabeçalho sai e a página ganha altura. Ela mostra três ou mais linhas inteiras, e uma seleção não muda essa
     * conta.
     */
    @Test
    fun p13_paisagemComTelaCheiaMostraAsLinhasDaAltura() {
        comPaginas {
            comFonteGrande {
                comRotacaoManual {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    girarParaPaisagem(scenario, comGuiada = true)
                    virarPaginaComGuiadaEmManual(scenario, indice = null)
                    esperarAte(scenario, "cabeçalho recolhido depois da virada") { a ->
                        a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.GONE &&
                            !a.findViewById<View>(R.id.recyclerViewParagrafos).isLayoutRequested
                    }
                    esperarPaginaParada(scenario)
                    var semSelecao = -1
                    scenario.onActivity { a ->
                        semSelecao = linhasInteirasVisiveis(a)
                        Capturas.anotar("p13", medirTelaPequena(a).detalhes)
                    }
                    assertTrue(
                        "Com o cabeçalho recolhido deveriam caber 3 linhas inteiras, couberam $semSelecao",
                        semSelecao >= 3,
                    )

                    val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
                    selecionarPalavra(scenario, regras, indice, palavra, cenario = "p13")
                    esperarAte(scenario, "barra da seleção visível") { a ->
                        a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.VISIBLE
                    }
                    var comSelecao = -1
                    var visibilidadeDoCabecalho = -1
                    scenario.onActivity { a ->
                        comSelecao = linhasInteirasVisiveis(a)
                        visibilidadeDoCabecalho = a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility
                    }
                    assertEquals(
                        "Com a seleção aberta o cabeçalho continua recolhido",
                        View.GONE,
                        visibilidadeDoCabecalho,
                    )
                    assertEquals("A seleção não deveria mudar o número de linhas inteiras", semSelecao, comSelecao)
                    Capturas.capturar("p13_selecao")
                }
            }
        }
    }

    /**
     * P11 (rb-26b, passo 5): paisagem com a guiada tocando e a fonte 1,3. O painel da guiada tem de medir até 72 dp e
     * os cinco botões dele têm de caber inteiros na tela. Depois, com uma seleção aberta, os rótulos da barra da
     * seleção não podem ter reticências (cada rótulo numa linha só, sem corte).
     */
    @Test
    fun p11_paisagemComGuiadaPainelCompactoERotulosSemReticencias() {
        comPaginas {
            comFonteGrande {
                comRotacaoManual {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    girarParaPaisagem(scenario, comGuiada = true)
                    esperarGuiadaTocando(scenario, indice = null)
                    esperarPaginaComLinhas(scenario)
                    esperarPaginaParada(scenario)
                    regras.fecharDialogosDoSistema()

                    val problemasDoPainel = mutableListOf<String>()
                    scenario.onActivity { a -> problemasDoPainel += problemasDoPainelDaGuiada(a) }
                    Capturas.capturar("p11_paisagem_guiada_painel")

                    val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
                    selecionarPalavra(scenario, regras, indice, palavra, cenario = "p11")
                    regras.fecharDialogosDoSistema()
                    esperarAte(scenario, "barra da seleção com os rótulos desenhados") { a ->
                        a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.VISIBLE &&
                            ROTULOS_DA_BARRA.all { (_, id) -> a.findViewById<TextView>(id).layout != null }
                    }
                    val reticencias = mutableListOf<String>()
                    scenario.onActivity { a ->
                        ROTULOS_DA_BARRA.forEach { (nome, id) ->
                            val layout = checkNotNull(a.findViewById<TextView>(id).layout)
                            val total = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
                            Capturas.anotar("p11", "'$nome' linhas=${layout.lineCount} reticencias=$total")
                            if (total > 0) reticencias += "'$nome' com $total caractere(s) cortado(s)"
                        }
                    }
                    Capturas.capturar("p11_paisagem_guiada_selecao")

                    val painel = problemasDoPainel.joinToString("; ")
                    assertTrue("Painel da guiada na paisagem: $painel", problemasDoPainel.isEmpty())
                    val rotulos = reticencias.joinToString("; ")
                    assertTrue("Rótulos da barra da seleção na paisagem: $rotulos", reticencias.isEmpty())
                }
            }
        }
    }

    /** Problemas do painel da guiada na paisagem: altura até 72 dp e os cinco botões inteiros dentro da tela. */
    private fun problemasDoPainelDaGuiada(a: ReaderActivity): List<String> {
        val problemas = mutableListOf<String>()
        val painel = a.findViewById<View>(R.id.layoutGuidedPlayer)
        if (painel.visibility != View.VISIBLE) problemas += "o painel não está visível"
        val teto = a.dpToPx(TETO_DO_PAINEL_NA_PAISAGEM_DP)
        Capturas.anotar("p11", "painel altura=${painel.height}px (teto $teto px)")
        if (painel.height > teto) problemas += "o painel mede ${painel.height} px, acima de $teto px (72 dp)"
        val larguraTela = a.resources.displayMetrics.widthPixels
        val alturaTela = a.resources.displayMetrics.heightPixels
        BOTOES_DO_PAINEL.forEach { (nome, id) ->
            val botao = a.findViewById<View>(id)
            if (botao.visibility != View.VISIBLE) {
                problemas += "'$nome' escondido"
                return@forEach
            }
            val retangulo = Rect()
            val visivel = botao.getGlobalVisibleRect(retangulo)
            Capturas.anotar("p11", "'$nome' rect=${retangulo.toShortString()} tamanho=${botao.width}x${botao.height}")
            val inteiro = visivel && retangulo.width() == botao.width && retangulo.height() == botao.height &&
                retangulo.left >= 0 && retangulo.top >= 0 &&
                retangulo.right <= larguraTela && retangulo.bottom <= alturaTela
            if (!inteiro) problemas += "'$nome' não cabe inteiro na tela: ${retangulo.toShortString()}"
        }
        return problemas
    }

    /**
     * Mede a tela, anota a medida com o prefixo [nome], captura a tela como [nomeDaCaptura] e confere só que a tela
     * abriu e que a lista tem linhas (sem asserção de valor: é a linha de base).
     */
    private fun registrar(scenario: ActivityScenario<ReaderActivity>, nome: String, nomeDaCaptura: String, descricao: String) {
        regras.fecharDialogosDoSistema()
        val medida = medir(scenario)
        var inteirasAgora = -1
        scenario.onActivity { a -> inteirasAgora = linhasInteirasVisiveis(a) }
        Capturas.anotar(nome, "$descricao: ${medida.detalhes}")
        Capturas.anotar(nome, "$descricao: linhasInteirasVisiveis=$inteirasAgora")
        Capturas.capturar(nomeDaCaptura)
        assertTrue("A lista do Leitor deveria estar no modo páginas ($descricao)", medida.paginaAtiva)
        assertTrue("A lista do Leitor deveria ter linhas na tela ($descricao)", medida.linhasNaLista > 0)
    }

    /** Quantas linhas inteiras da página cabem na tela agora (ver [medirTelaPequena]), na thread principal. */
    private fun linhasInteirasVisiveis(a: ReaderActivity): Int = medirTelaPequena(a).linhasInteiras

    /**
     * Liga a leitura guiada tocando e a deixa em modo manual: pausa a guiada (como no P5, para o arraste medir
     * sem a página andando), vira uma página com um arraste lateral e espera o "Voltar à narração" do painel.
     */
    private fun virarPaginaComGuiadaEmManual(scenario: ActivityScenario<ReaderActivity>, indice: Int?) {
        esperarGuiadaTocando(scenario, indice)
        esperarPaginaComLinhas(scenario)
        regras.fecharDialogosDoSistema()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
        val antes = medir(scenario).offset
        arrastarLateral(scenario, paraFrente = true)
        esperarAte(scenario, "a página virar com o arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
            a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos).computeVerticalScrollOffset() > antes
        }
        esperarAte(
            scenario,
            "'Voltar à narração' do painel visível depois do arraste lateral",
            TEMPO_DE_VIRADA_MS,
        ) { a ->
            a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
        }
        regras.fecharDialogosDoSistema()
    }

    /**
     * Espera a guiada tocando: o parágrafo [indice] ou, com [indice] nulo, qualquer parágrafo (depois de um giro, o
     * índice não importa: a guiada segue lendo enquanto o giro assenta). Estado lido direto do manager, sem onView.
     */
    private fun esperarGuiadaTocando(scenario: ActivityScenario<ReaderActivity>, indice: Int?) {
        val descricao = if (indice == null) "leitura guiada tocando" else "leitura guiada tocando o parágrafo $indice"
        esperarAte(scenario, descricao) { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let {
                it.isPlaying && (if (indice == null) it.index >= 0 else it.index == indice)
            } == true
        }
    }

    /** Espera a lista do Leitor ter parágrafos montados, a paginação ativa e linhas na tela. */
    private fun esperarPaginaComLinhas(scenario: ActivityScenario<ReaderActivity>) {
        esperarAte(scenario, "páginas com linhas na tela") { a ->
            val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
            lista.adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size &&
                lista.paginacao?.ativa == true &&
                lista.linhasNaTela().isNotEmpty()
        }
    }

    /**
     * Espera o deslocamento da página parar (a guiada pode virar a página sozinha no meio da medida): cinco leituras
     * iguais seguidas, a cada 200 ms, ou até [TEMPO_MAXIMO_PAGINA_PARADA_MS]. Quem chama anota o deslocamento medido.
     */
    private fun esperarPaginaParada(scenario: ActivityScenario<ReaderActivity>) {
        var anterior = -1
        var iguais = 0
        val fim = System.currentTimeMillis() + TEMPO_MAXIMO_PAGINA_PARADA_MS
        while (System.currentTimeMillis() < fim) {
            var atual = -1
            scenario.onActivity { a ->
                atual = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos).computeVerticalScrollOffset()
            }
            iguais = if (atual == anterior) iguais + 1 else 0
            anterior = atual
            if (iguais >= LEITURAS_IGUAIS_PARA_PARAR) return
            Thread.sleep(INTERVALO_DE_PAGINA_PARADA_MS)
        }
    }

    /** Abre o livro no Só ler (sem a leitura guiada), com o modo páginas já ligado pelo corpo do teste. */
    private fun abrirSoLerPaginado(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        return regras.abrir(intent)
    }

    /** Medida da tela na thread principal (ver [medirTelaPequena]). */
    private fun medir(scenario: ActivityScenario<ReaderActivity>): Medida {
        var medida: Medida? = null
        scenario.onActivity { a -> medida = medirTelaPequena(a) }
        return medida ?: throw AssertionError("Sem medida da tela pequena do Leitor")
    }

    /**
     * Linhas inteiras da página na tela, com o que sobra em cima e embaixo. Converte as linhas de [linhasNaTela] (nas
     * coordenadas da lista) para a tela com getLocationOnScreen, que já inclui a translação. Conta as linhas entre o
     * limite de cima (topo útil da lista, a base do cabeçalho se ele está visível, ou a base da barra da seleção se ela
     * está no topo) e o de baixo (o corte, o topo do painel da guiada, o topo do chip "Voltar à narração" e o topo da
     * barra da seleção se ela está embaixo).
     */
    private fun medirTelaPequena(a: ReaderActivity): Medida {
        val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
        val paginacao = checkNotNull(lista.paginacao) { "A lista do Leitor não tem paginação" }
        val corte = paginacao.corte()
        val topoLista = topoNaTela(lista)
        val meio = a.resources.displayMetrics.heightPixels / 2

        var limiteDeCima = topoLista + lista.paddingTop
        var limiteDeBaixo = topoLista + corte

        // Cabeçalho sobreposto (rb-66): quando visível, cobre o topo da lista, então também conta como limite de cima.
        val cabecalho = a.findViewById<View>(R.id.cabecalhoDoLeitor)
        if (cabecalho.visibility == View.VISIBLE) limiteDeCima = maxOf(limiteDeCima, baseNaTela(cabecalho))

        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val barraVisivel = barra.visibility == View.VISIBLE
        if (barraVisivel) {
            if (topoNaTela(barra) < meio) {
                limiteDeCima = maxOf(limiteDeCima, baseNaTela(barra))
            } else {
                limiteDeBaixo = minOf(limiteDeBaixo, topoNaTela(barra))
            }
        }

        val painel = a.findViewById<View>(R.id.layoutGuidedPlayer)
        val painelVisivel = painel.visibility == View.VISIBLE
        if (painelVisivel) limiteDeBaixo = minOf(limiteDeBaixo, topoNaTela(painel))

        val chip = a.findViewById<View>(R.id.fabVoltarNarracao)
        val chipVisivel = chip.visibility == View.VISIBLE
        if (chipVisivel) limiteDeBaixo = minOf(limiteDeBaixo, topoNaTela(chip))

        val linhas = lista.linhasNaTela()
        val inteiras = linhas.count { linha ->
            val topo = topoLista + linha.topo
            val base = topoLista + linha.base
            topo >= limiteDeCima && base <= limiteDeBaixo
        }
        val primeira = linhas.filter { it.base > lista.paddingTop }.minByOrNull { it.topo }
        val folgaEmCima = primeira?.let { it.topo - lista.paddingTop }

        val play = a.findViewById<View>(R.id.btnGuidedPlayPause)
        val sobraEmbaixoDoPainel = if (painelVisivel) baseNaTela(painel) - baseNaTela(play) else null

        val detalhes = "linhasInteiras=$inteiras linhasNaLista=${linhas.size} " +
            "folgaEmCima=${folgaEmCima ?: "sem linha"} sobraEmbaixoDoPainel=${sobraEmbaixoDoPainel ?: "sem painel"} " +
            "limiteCima=$limiteDeCima limiteBaixo=$limiteDeBaixo topoLista=$topoLista paddingTop=${lista.paddingTop} " +
            "corte=$corte offset=${lista.computeVerticalScrollOffset()} paginaAtiva=${paginacao.ativa} " +
            "barra=${if (barraVisivel) (if (topoNaTela(barra) < meio) "topo" else "embaixo") else "oculta"} " +
            "painel=$painelVisivel chip=$chipVisivel telaAltura=${a.resources.displayMetrics.heightPixels}"
        return Medida(
            linhasInteiras = inteiras,
            linhasNaLista = linhas.size,
            folgaEmCima = folgaEmCima,
            sobraEmbaixoDoPainel = sobraEmbaixoDoPainel,
            offset = lista.computeVerticalScrollOffset(),
            paginaAtiva = paginacao.ativa,
            detalhes = detalhes,
        )
    }

    /** Topo da view na tela, em pixels (getLocationOnScreen, com a translação). */
    private fun topoNaTela(view: View): Int = IntArray(2).also { view.getLocationOnScreen(it) }[1]

    /** Base da view na tela, em pixels. */
    private fun baseNaTela(view: View): Int = topoNaTela(view) + view.height

    /**
     * Liga a fonte grande (1,3) para o corpo. Não a devolve no fim: a Activity ainda está aberta ali, e mudar a fonte
     * recriaria o Leitor antes do fechamento. O [RegrasDoLeitor] restaura a escala lida antes do teste, depois do
     * fechamento do Leitor.
     */
    private fun comFonteGrande(corpo: () -> Unit) {
        regras.comandoDoShell("settings put system font_scale 1.3")
        corpo()
    }

    /** Liga a opção "Horizontal (páginas)" para o corpo, e a desliga no fim (mesmo se o teste falhar). */
    private fun comPaginas(corpo: () -> Unit) {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        try {
            corpo()
        } finally {
            AppPrefs(contexto).readerRolagemHorizontal = false
        }
    }

    /**
     * Gira para a paisagem com o Leitor JÁ aberto. Antes de abrir o Leitor, a tela da frente é o launcher do emulador,
     * que fica preso no retrato e não gira. Espera o Leitor antigo ficar pronto, gira, espera a rotação e a Activity
     * nova ficar pronta (com [comGuiada], o painel da guiada visível). Usar dentro de [comRotacaoManual].
     */
    private fun girarParaPaisagem(scenario: ActivityScenario<ReaderActivity>, comGuiada: Boolean) {
        val antes = esperarLeitorPronto(scenario, comGuiada, antes = null, momento = "antes do giro")
        device.setOrientationLeft()
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        while (device.displayRotation == Surface.ROTATION_0 && System.currentTimeMillis() < fim) {
            Thread.sleep(INTERVALO_DE_ROTACAO_MS)
        }
        assertTrue(
            "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
            device.displayRotation != Surface.ROTATION_0,
        )
        esperarLeitorPronto(scenario, comGuiada, antes = antes, momento = "paisagem")
        Thread.sleep(PAUSA_APOS_GIRO_MS)
    }

    /**
     * Espera uma Activity do Leitor pronta para medir: lista com páginas ativas e linhas na tela e, com [comGuiada],
     * o painel da guiada visível. Com [antes] nulo aceita qualquer Activity; senão exige outra instância. Devolve a
     * identidade da Activity pronta. Durante a recriação não há Activity para ler (ver [lerAtividade]): tenta de novo.
     */
    private fun esperarLeitorPronto(
        scenario: ActivityScenario<ReaderActivity>,
        comGuiada: Boolean,
        antes: Int?,
        momento: String,
    ): Int {
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        var visto = "nenhuma Activity disponível"
        while (System.currentTimeMillis() < fim) {
            var pronta: Int? = null
            lerAtividade(scenario) { a ->
                val identidade = System.identityHashCode(a)
                val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
                val painel = a.findViewById<View>(R.id.layoutGuidedPlayer).visibility
                visto = "nova=${identidade != antes} paginaAtiva=${lista.paginacao?.ativa} " +
                    "linhas=${lista.linhasNaTela().size} painelGuiada=$painel"
                val nova = antes == null || identidade != antes
                val painelOk = !comGuiada || painel == View.VISIBLE
                if (nova && painelOk && lista.paginacao?.ativa == true && lista.linhasNaTela().isNotEmpty()) {
                    pronta = identidade
                }
            }
            pronta?.let { return it }
            Thread.sleep(INTERVALO_DE_ROTACAO_MS)
        }
        throw AssertionError("O Leitor não ficou pronto $momento em ${TEMPO_LIMITE_ESPERA_MS / 1000} s. Viu: $visto")
    }

    /**
     * Roda [leitura] na Activity atual. Logo depois de um giro, o ActivityScenario pode estar com a instância antiga
     * destruída, ou com a nova ainda sem resumir: aí onActivity lança IllegalStateException, e a função devolve nulo.
     */
    private fun <T> lerAtividade(scenario: ActivityScenario<ReaderActivity>, leitura: (ReaderActivity) -> T): T? {
        var resultado: T? = null
        try {
            scenario.onActivity { a -> resultado = leitura(a) }
        } catch (erro: IllegalStateException) {
            return null
        }
        return resultado
    }

    /**
     * Desliga a rotação automática antes de [corpo] e, no fim (mesmo se falhar), volta à orientação natural e restaura
     * o ajuste de rotação automática que estava antes. Mesma regra do PaginasNoAparelhoTest.
     */
    private fun comRotacaoManual(corpo: () -> Unit) {
        val automaticaAntes = regras.comandoDoShell("settings get system accelerometer_rotation").trim()
        regras.comandoDoShell("settings put system accelerometer_rotation 0")
        try {
            corpo()
        } finally {
            runCatching {
                device.setOrientationNatural()
                device.unfreezeRotation()
            }
            runCatching {
                if (automaticaAntes.isEmpty() || automaticaAntes == "null") {
                    regras.comandoDoShell("settings delete system accelerometer_rotation")
                } else {
                    regras.comandoDoShell("settings put system accelerometer_rotation $automaticaAntes")
                }
            }
        }
    }

    /**
     * Arraste lateral de quase toda a largura da lista, começando perto do centro e sem mudar de altura. [paraFrente] é
     * o dedo da direita para a esquerda, que vira a página para a frente (mesma regra do PaginasNoAparelhoTest).
     */
    private fun arrastarLateral(scenario: ActivityScenario<ReaderActivity>, paraFrente: Boolean) {
        val area = Rect()
        var densidade = 1f
        scenario.onActivity { a ->
            a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos).getGlobalVisibleRect(area)
            densidade = a.resources.displayMetrics.density
        }
        val distancia = area.width() * FRACAO_DO_ARRASTE_LATERAL * 2
        check(distancia >= DISTANCIA_MINIMA_DP * densidade) {
            "A lista é estreita demais para um arraste de ${DISTANCIA_MINIMA_DP.toInt()} dp: ${area.width()} px"
        }
        val meia = distancia / 2f
        val centroX = area.centerX().toFloat()
        val y = area.centerY().toFloat()
        val xInicio = if (paraFrente) centroX + meia else centroX - meia
        val xFim = if (paraFrente) centroX - meia else centroX + meia
        regras.fecharDialogosDoSistema()
        Gestos.arrastar(xInicio, y, xFim, y)
    }

    /** Medida de uma tela: linhas inteiras que cabem, folgas e o que sobra; [detalhes] vai para medidas.txt. */
    private class Medida(
        val linhasInteiras: Int,
        val linhasNaLista: Int,
        val folgaEmCima: Int?,
        val sobraEmbaixoDoPainel: Int?,
        val offset: Int,
        val paginaAtiva: Boolean,
        val detalhes: String,
    )

    private companion object {
        /** Fração da largura da lista que cada arraste lateral anda para cada lado do centro (0,34 no total). */
        const val FRACAO_DO_ARRASTE_LATERAL = 0.17f

        /** Distância mínima do arraste lateral, em dp, para contar como virada (ver GestoArraste). */
        const val DISTANCIA_MINIMA_DP = 120f

        /** Tempo para uma virada feita pelo dedo aparecer na lista. */
        const val TEMPO_DE_VIRADA_MS = 5_000L

        /** Pausa depois do giro, para o layout e o alinhamento da página assentarem antes de medir. */
        const val PAUSA_APOS_GIRO_MS = 500L

        /** Intervalo de checagem da rotação, em ms. */
        const val INTERVALO_DE_ROTACAO_MS = 100L

        /** Até quando esperar a página parar antes da medida, com a guiada tocando. */
        const val TEMPO_MAXIMO_PAGINA_PARADA_MS = 15_000L

        /** Leituras iguais seguidas que dão a página por parada. */
        const val LEITURAS_IGUAIS_PARA_PARAR = 5

        /** Intervalo entre as leituras do deslocamento, em ms. */
        const val INTERVALO_DE_PAGINA_PARADA_MS = 200L

        /** Teto da altura do painel da guiada na paisagem, em dp (rb-26b, passo 5). */
        const val TETO_DO_PAINEL_NA_PAISAGEM_DP = 72

        /** Os cinco botões do painel da guiada, com o nome que aparece nas mensagens de falha. */
        val BOTOES_DO_PAINEL = listOf(
            "Voz" to R.id.btnGuidedVoz,
            "Anterior" to R.id.btnGuidedPrev,
            "Tocar/pausar" to R.id.btnGuidedPlayPause,
            "Próximo" to R.id.btnGuidedNext,
            "Fechar" to R.id.btnGuidedClose,
        )

        /** Os cinco rótulos da barra da seleção com áudio (rb-61), com o nome das mensagens de falha. */
        val ROTULOS_DA_BARRA = listOf(
            "Gerar áudio" to R.id.btnSelecaoTextoGerar,
            "Destacar" to R.id.btnSelecaoTextoDestacar,
            "Pronúncia" to R.id.btnSelecaoTextoPronuncia,
            "Definir" to R.id.btnSelecaoTextoDefinir,
            "Vários parágrafos" to R.id.btnSelecaoTextoVarios,
        )
    }
}
