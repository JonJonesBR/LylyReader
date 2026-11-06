package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.RegraDasPaginasDoLeitor
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.TEMPO_LIMITE_ESPERA_MS
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.arrastarAlcaFinalAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.fundoParaEscolherPalavra
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Modo páginas do Leitor no emulador (rb-26b, passo 4): com a opção "Horizontal (páginas)" ligada, a lista continua
 * vertical e o que vira é a página virtual (ver [PaginacaoDoLeitor]). P1 confere o corte e a orientação; P2 e P3
 * viram a página com o arraste lateral e confere que o vertical não rola; P4 confere que a seleção e a alça não
 * viram a página; P5 é a leitura guiada virando sozinha e o botão "Voltar à narração" no painel; P6 gira a tela; P7
 * confere PAGE_DOWN; P8 põe a alça na linha de baixo da página, com a guiada pausada, e confere que a página não rola;
 * P9 e P10 são a seleção com a barra embaixo, no retrato e na paisagem (rb-26b, passo 3); P12 é o cabeçalho que some
 * ao virar a página e volta com um deslize para baixo (rb-26b, passo 6); P14 é o botão "Voltar à narração" do painel na
 * paisagem, com a guiada pausada e longe da narração, que tem de levar a página até a linha narrada (rb-26b, rodada 3).
 * Os toques são reais (ver [Gestos]); as conferências leem as Views na thread principal.
 */
@RunWith(AndroidJUnit4::class)
class PaginasNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor. A preferência do modo páginas é gravada no
    // corpo de cada teste (ver [comPaginas]), porque a regra apaga as preferências antes dele.
    @get:Rule
    val regras = RegrasDoLeitor()

    private val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /** P1: a 1ª página mostra dois ou mais parágrafos, nenhuma linha visível cruza o corte, e a lista é vertical. */
    @Test
    fun p1_primeiraPaginaSemLinhaCortada() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            val pagina = medirPagina(scenario)
            val paragrafos = pagina.linhas
                .filter { it.base > pagina.topo && it.topo < pagina.fundo }
                .map { it.paragrafo }
                .distinct()
            Capturas.anotar(
                "p1",
                "pagina: offset=${pagina.offset} topo=${pagina.topo} fundo=${pagina.fundo} corte=${pagina.corte} " +
                    "paragrafos=$paragrafos vertical=${pagina.vertical} ativa=${pagina.ativa}",
            )
            Capturas.capturar("70_p1_primeira_pagina")
            assertTrue("A lista do Leitor deveria ser vertical no modo páginas", pagina.vertical)
            assertTrue("O modo páginas deveria estar ativo", pagina.ativa)
            assertTrue(
                "A 1ª página deveria mostrar 2 ou mais parágrafos, mostrou $paragrafos",
                paragrafos.size >= 2,
            )
            conferirSemLinhaCortada(pagina, "p1")
        }
    }

    /**
     * P2: um arraste lateral da direita para a esquerda vira a página: o deslocamento aumenta e a 1ª linha do topo é a
     * que estava escondida antes. O arraste da esquerda para a direita volta: o deslocamento diminui e a linha que
     * estava no topo antes de avançar aparece inteira de novo.
     */
    @Test
    fun p2_arrasteLateralVireEVoltaAPagina() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            val antes = medirPagina(scenario)
            val escondidaAntes = checkNotNull(antes.primeiraEscondida()) {
                "A 1ª página não tem linha escondida para virar"
            }
            val topoAntes = checkNotNull(antes.topoVisivel()) { "A 1ª página não tem linha no topo" }
            Capturas.anotar(
                "p2",
                "antes: offset=${antes.offset} topo=$topoAntes escondida=$escondidaAntes corte=${antes.corte}",
            )

            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "a página virar com o arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
                offsetDaLista(a) > antes.offset
            }
            val avancou = medirPagina(scenario)
            Capturas.anotar("p2", "avancou: offset=${avancou.offset} topo=${avancou.topoVisivel()} corte=${avancou.corte}")
            Capturas.capturar("71_p2_virou_com_arraste")
            assertTrue(
                "O arraste lateral deveria aumentar o deslocamento (${antes.offset} -> ${avancou.offset})",
                avancou.offset > antes.offset,
            )
            assertTrue(
                "A 1ª linha do topo deveria ser a que estava escondida antes " +
                    "(${escondidaAntes.paragrafo}/${escondidaAntes.numero}), veio ${avancou.topoVisivel()}",
                escondidaAntes.mesmaQue(avancou.topoVisivel()),
            )
            conferirSemLinhaCortada(avancou, "p2-avancou")

            arrastarLateral(scenario, paraFrente = false)
            esperarAte(scenario, "a página voltar com o arraste para a direita", TEMPO_DE_VIRADA_MS) { a ->
                offsetDaLista(a) < avancou.offset
            }
            val voltou = medirPagina(scenario)
            val linhaDeAntes = voltou.linhas.firstOrNull { topoAntes.mesmaQue(it) }
            Capturas.anotar("p2", "voltou: offset=${voltou.offset} linhaDoTopoAntes=$linhaDeAntes")
            Capturas.capturar("72_p2_voltou")
            assertTrue(
                "O arraste para a direita deveria diminuir o deslocamento (${avancou.offset} -> ${voltou.offset})",
                voltou.offset < avancou.offset,
            )
            assertTrue(
                "A linha que estava no topo antes de avançar deveria aparecer inteira de novo, veio $linhaDeAntes",
                linhaDeAntes != null && linhaDeAntes.topo >= voltou.topo && linhaDeAntes.base <= voltou.fundo,
            )
            conferirSemLinhaCortada(voltou, "p2-voltou")
        }
    }

    /** P3: um arraste vertical não muda o deslocamento da página (a lista não rola no modo páginas). */
    @Test
    fun p3_arrasteVerticalNaoRolaAPagina() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            val antes = medirPagina(scenario)
            rolarListaComODedo(scenario, fracaoDaAreaUtil = FRACAO_DO_ARRASTE_VERTICAL, paraBaixoNoTexto = true, cenario = "p3")
            val depois = medirPagina(scenario)
            Capturas.anotar("p3", "offset ${antes.offset} -> ${depois.offset}")
            Capturas.capturar("73_p3_depois_do_arraste_vertical")
            assertEquals("O arraste vertical não deveria rolar a página", antes.offset, depois.offset)
            conferirSemLinhaCortada(depois, "p3")
        }
    }

    /**
     * P4: um toque longo numa palavra visível seleciona (uma seleção, com a barra visível, como no C02), e arrastar a
     * alça do fim amplia a seleção (como no C03). Nem a seleção nem a alça viram a página.
     */
    @Test
    fun p4_selecaoEAlcaNaoViramAPagina() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            val antes = medirPagina(scenario)

            selecionarPalavra(scenario, regras, 0, PALAVRA, cenario = "p4")
            val selecao = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção depois do toque longo em '$PALAVRA'" }
            regras.fecharDialogosDoSistema()
            scenario.onActivity { a ->
                assertEquals(
                    "Deveria haver uma seleção aberta",
                    1,
                    listaDoLeitor(a).selecoesAbertas,
                )
                assertEquals(
                    "A barra da seleção deveria estar visível",
                    View.VISIBLE,
                    a.findViewById<View>(R.id.barraSelecaoTexto).visibility,
                )
            }
            val depoisDaSelecao = medirPagina(scenario)
            Capturas.anotar(
                "p4",
                "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}' " +
                    "offset ${antes.offset} -> ${depoisDaSelecao.offset}",
            )
            Capturas.capturar("74_p4_selecao")
            assertEquals("Selecionar a palavra não deveria virar a página", antes.offset, depoisDaSelecao.offset)

            val resultado = arrastarAlcaFinalAte(
                scenario = scenario,
                indiceParagrafo = 0,
                palavraAlvo = PALAVRA_ADIANTE,
                antes = selecao,
                regras = regras,
                reselecionar = { selecionarPalavra(scenario, regras, 0, PALAVRA, cenario = "p4") },
                anotar = { Capturas.anotar("p4", it) },
            )
            val depois = resultado.depois ?: throw AssertionError(
                "Arrastar a alça não ampliou a seleção até '$PALAVRA_ADIANTE'. " +
                    "Tentativas: ${resultado.tentativas.joinToString("; ")}",
            )
            val depoisDoArraste = medirPagina(scenario)
            Capturas.anotar(
                "p4",
                "alca: [${depois.inicio},${depois.fim}] texto='${depois.texto}' offset ${depoisDoArraste.offset}",
            )
            Capturas.capturar("75_p4_alca_ampliada")
            assertTrue(
                "A alça deveria ampliar a seleção até '$PALAVRA_ADIANTE', veio '${depois.texto}'",
                depois.fim > selecao.fim,
            )
            assertEquals(
                "Arrastar a alça não deveria virar a página",
                antes.offset,
                depoisDoArraste.offset,
            )
        }
    }

    /**
     * P5: com a voz falsa, a guiada narra e, quando a linha narrada passa do corte, a página vira sozinha (o
     * deslocamento aumenta) e o parágrafo narrado continua na tela. Um arraste lateral pelo leitor liga o botão "Voltar
     * à narração", e tocar nele leva à página do narrado.
     */
    @Test
    fun p5_guiadaViraAPaginaEOBotaoVoltaAoNarrado() {
        comPaginas {
            val contexto = InstrumentationRegistry.getInstrumentation().targetContext
            // Trechos de 6 s (e não a voz longa de 30 s): a guiada precisa passar do corte dentro do tempo do teste.
            regras.usarVoz(
                VozFalsa(
                    contexto,
                    msPorCaractere = 0.0,
                    minMs = VOZ_POR_TRECHO_P5_MS,
                    maxMs = VOZ_POR_TRECHO_P5_MS,
                ),
            )
            // Livro dobrado: sempre sobra página adiante quando o arraste lateral acontecer, mesmo com a leitura lenta.
            val scenario = abrirLeitorComGuiada(
                regras,
                contexto,
                textos = LivroNoAparelho.PARAGRAFOS + LivroNoAparelho.PARAGRAFOS,
            )
            esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
            }
            regras.fecharDialogosDoSistema()
            val antes = medirPagina(scenario)
            Capturas.anotar("p5", "guiada no começo: offset=${antes.offset} topo=${antes.topoVisivel()}")

            esperarAte(scenario, "a guiada virar a página sozinha ao passar do corte", TEMPO_DA_GUIADA_MS) { a ->
                offsetDaLista(a) > antes.offset
            }
            regras.fecharDialogosDoSistema()
            esperarAte(scenario, "o parágrafo narrado continuar na tela depois da virada", TEMPO_DE_VIRADA_MS) { a ->
                narradoNaPagina(a)?.visivel == true
            }
            val virou = medirPagina(scenario)
            val narrado = lerAtividade(scenario) { narradoNaPagina(it) }
            Capturas.anotar("p5", "guiada virou: offset ${antes.offset} -> ${virou.offset} narrado=$narrado")
            Capturas.capturar("76_p5_guiada_virou")
            assertTrue(
                "A guiada deveria virar a página ao passar do corte (${antes.offset} -> ${virou.offset})",
                virou.offset > antes.offset,
            )
            assertTrue(
                "O parágrafo narrado deveria continuar na tela depois da virada, veio $narrado",
                narrado?.visivel == true,
            )
            conferirSemLinhaCortada(virou, "p5-virou")

            // Pausa a guiada antes do arraste: com ela tocando, a página e o narrado andam a cada ~50 ms e a medida
            // do arraste sai instável. pause() mantém o índice narrado, então o botão ainda consegue levar até ele.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }

            // O leitor vira a página com o dedo: a guiada entra em modo manual e o "Voltar à narração" aparece no
            // painel (rb-26b, passo 4). O chip flutuante fica de fora no modo páginas.
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(
                scenario,
                "botão 'Voltar à narração' do painel visível depois do arraste lateral",
                TEMPO_DE_VIRADA_MS,
            ) { a ->
                a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
            }
            scenario.onActivity { a ->
                assertEquals(View.GONE, a.findViewById<View>(R.id.fabVoltarNarracao).visibility)
            }
            Capturas.capturar("77_p5_botao_voltar")
            // Antes do toque, o narrado tem de estar fora da página: é isso que o botão sinaliza. Nulo quer dizer que o
            // parágrafo nem está montado na lista, ou seja, também está fora da página.
            // Leitura separada da falha: se onActivity lançar (Activity indisponível), lido continua falso.
            var foraAntes: Narrado? = null
            var lido = false
            try {
                scenario.onActivity { a ->
                    foraAntes = narradoNaPagina(a)
                    lido = true
                }
            } catch (erro: IllegalStateException) {
                Capturas.anotar("p5", "antes do botão: leitura da tela falhou (${erro.message})")
            }
            Capturas.anotar("p5", "antes do botão: narrado=$foraAntes lido=$lido")
            assertTrue("Não consegui ler a tela antes do botão", lido)
            assertTrue(
                "O narrado deveria estar fora da página antes do toque no botão " +
                    "(nulo = parágrafo fora da lista montada), veio $foraAntes",
                foraAntes?.visivel != true,
            )

            val (x, y) = Gestos.centroDaView(
                scenario,
                R.id.btnGuidedVoltarNarracao,
                "botão Voltar à narração do painel",
            )
            regras.fecharDialogosDoSistema()
            device.click(x, y)
            esperarAte(scenario, "o botão levar à página do parágrafo narrado", TEMPO_DE_VIRADA_MS) { a ->
                narradoNaPagina(a)?.visivel == true
            }
            val depoisDoBotao = lerAtividade(scenario) { narradoNaPagina(it) }
            Capturas.anotar("p5", "depois do botão: narrado=$depoisDoBotao")
            Capturas.capturar("78_p5_depois_do_botao")
            assertTrue(
                "O toque no botão deveria levar o narrado de volta à página, veio $depoisDoBotao",
                depoisDoBotao?.visivel == true,
            )
        }
    }

    /**
     * P6: girar para a paisagem e voltar ao retrato deixa a página sem linha cortada, e o parágrafo que estava no topo
     * antes do giro continua na tela.
     */
    @Test
    fun p6_girarEVoltarMantemAPaginaEOParagrafoDeReferencia() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            // Vira uma página antes de girar, para o parágrafo de referência não ser sempre o 0 (offset 0).
            val primeira = medirPagina(scenario)
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "a página virar antes do giro", TEMPO_DE_VIRADA_MS) { a ->
                offsetDaLista(a) > primeira.offset
            }
            // Medida depois da virada: o parágrafo 0 pode já ter saído da tela, e a referência é o que está no topo agora.
            val virada = medirPagina(scenario)
            val referencia = checkNotNull(virada.topoVisivel()) {
                "Sem linha no topo antes de girar"
            }.paragrafo
            Capturas.anotar("p6", "antes do giro: offset=${virada.offset} paragrafo de referencia=$referencia")

            comRotacaoManual {
                val antesDoGiro = instanciaAtual(scenario)
                device.setOrientationLeft()
                esperarPaginaRecriada(scenario, antesDoGiro, "paisagem")
                Thread.sleep(PAUSA_APOS_GIRO_MS)
                val naPaisagem = medirPagina(scenario)
                Capturas.anotar("p6", "paisagem: rotacao=${device.displayRotation} corte=${naPaisagem.corte}")
                Capturas.capturar("79_p6_paisagem")
                assertTrue(
                    "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
                    device.displayRotation != Surface.ROTATION_0,
                )
                conferirSemLinhaCortada(naPaisagem, "p6-paisagem")

                val antesDoRetorno = instanciaAtual(scenario)
                device.setOrientationNatural()
                esperarPaginaRecriada(scenario, antesDoRetorno, "retrato")
                Thread.sleep(PAUSA_APOS_GIRO_MS)
                val noRetrato = medirPagina(scenario)
                Capturas.anotar("p6", "retrato: rotacao=${device.displayRotation} corte=${noRetrato.corte}")
                Capturas.capturar("80_p6_retrato")
                assertEquals(
                    "Pedi o retrato, mas a rotação ficou em ${device.displayRotation}",
                    Surface.ROTATION_0,
                    device.displayRotation,
                )
                conferirSemLinhaCortada(noRetrato, "p6-retrato")
                assertTrue(
                    "O parágrafo $referencia deveria continuar na tela depois de girar, veio ${noRetrato.linhas}",
                    noRetrato.linhas.any { it.paragrafo == referencia && it.base > noRetrato.topo && it.topo < noRetrato.fundo },
                )
            }
        }
    }

    /** P7: a tecla PAGE_DOWN vira a página (o deslocamento aumenta) e a página nova não corta linha. */
    @Test
    fun p7_pageDownVireAPagina() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            // Com a janela em modo de toque, o 1º PAGE_DOWN é consumido pelo ViewRootImpl para sair desse modo (o foco
            // vai para a toolbar) e não chega à Activity: comportamento do Android, não do app. Por isso sai-se do modo
            // de toque antes de medir, e volta-se a ele no fim (mesmo se o teste falhar).
            val instrumentacao = InstrumentationRegistry.getInstrumentation()
            instrumentacao.setInTouchMode(false)
            instrumentacao.waitForIdleSync()
            try {
                esperarAte(scenario, "sair do modo de toque") { a -> !a.window.decorView.isInTouchMode }
                val antes = medirPagina(scenario)
                instrumentacao.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
                esperarAte(scenario, "PAGE_DOWN virar a página", TEMPO_DE_VIRADA_MS) { a ->
                    offsetDaLista(a) > antes.offset
                }
                val depois = medirPagina(scenario)
                Capturas.anotar("p7", "PAGE_DOWN: offset ${antes.offset} -> ${depois.offset}")
                Capturas.capturar("81_p7_page_down")
                assertTrue(
                    "PAGE_DOWN deveria virar a página (${antes.offset} -> ${depois.offset})",
                    depois.offset > antes.offset,
                )
                conferirSemLinhaCortada(depois, "p7")
            } finally {
                instrumentacao.setInTouchMode(true)
            }
        }
    }

    /**
     * P9 (rb-26b, passo 3), retrato: no modo páginas, com a guiada em modo manual e uma seleção, a barra da seleção
     * fica embaixo, no lugar do painel da guiada, e o texto não se mexe: o mesmo deslocamento, o mesmo corte e as
     * mesmas linhas antes e depois de abrir. A barra fica acima do corte. Voltar fecha a seleção e devolve o painel.
     */
    @Test
    fun p9_selecaoNoModoPaginasFicaEmbaixoSemMexerNoTexto() {
        comPaginas {
            val contexto = InstrumentationRegistry.getInstrumentation().targetContext
            regras.usarVoz(VozFalsa.longa(contexto))
            val scenario = abrirLeitorComGuiada(regras, contexto)
            esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
            }
            regras.fecharDialogosDoSistema()
            // Vira uma página à mão (modo manual, com o botão do painel), como no S2: o texto para e a seleção é feita sobre ele.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "botão 'Voltar à narração' do painel visível depois do arraste", TEMPO_DE_VIRADA_MS) { a ->
                a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.resume() }
            esperarPaginaParada(scenario)
            regras.fecharDialogosDoSistema()

            val antes = medirPagina(scenario)
            val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
            selecionarPalavra(scenario, regras, indice, palavra, cenario = "p9")
            regras.fecharDialogosDoSistema()
            esperarAte(scenario, "barra da seleção embaixo e o painel cedido", TEMPO_DE_VIRADA_MS) { a ->
                barraEmbaixo(a) && a.findViewById<View>(R.id.layoutGuidedPlayer).visibility == View.INVISIBLE
            }
            Thread.sleep(PAUSA_APOS_SELECAO_MS)
            val depois = medirPagina(scenario)
            Capturas.anotar("p9", "antes=${antes.offset}/${antes.corte} depois=${depois.offset}/${depois.corte}")
            Capturas.capturar("82_p9_selecao_embaixo")
            assertEquals("Abrir a seleção não deveria mexer no deslocamento da página", antes.offset, depois.offset)
            assertEquals("Abrir a seleção não deveria mudar o corte da página", antes.corte, depois.corte)
            assertEquals("Abrir a seleção não deveria mudar as linhas da página", antes.linhas, depois.linhas)

            val barraNaTela = lerAtividade(scenario) { a -> topoDaBarraNaTela(a) }
            val corteNaTela = lerAtividade(scenario) { a -> topoNaTela(listaDoLeitor(a)) + depois.corte }
            assertTrue(
                "A barra deveria ficar abaixo do corte: barra=$barraNaTela corte=$corteNaTela",
                barraNaTela != null && corteNaTela != null && barraNaTela >= corteNaTela,
            )

            // Voltar fecha a seleção: a barra some e o painel da guiada volta.
            var tentativas = 0
            while (tentativas < 2 && !barraSumiu(scenario)) {
                regras.fecharDialogosDoSistema()
                device.pressBack()
                if (esperarBarraSumir(scenario, TEMPO_DE_VIRADA_MS)) break
                tentativas++
            }
            esperarAte(scenario, "barra da seleção some e o painel da guiada volta") { a ->
                a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.GONE &&
                    a.findViewById<View>(R.id.layoutGuidedPlayer).visibility == View.VISIBLE
            }
            Capturas.capturar("83_p9_voltou")
            scenario.onActivity { a ->
                assertFalse("Voltar deveria fechar a seleção", listaDoLeitor(a).textoComSelecaoViva != null)
            }
        }
    }

    /**
     * P10 (rb-26b, passo 3), paisagem: com a guiada pausada e uma seleção, a barra fica embaixo e a página mantém
     * linhas inteiras acima dela: pelo menos uma linha inteira entre o topo útil e o menor entre o corte e o topo da
     * barra, e a linha da seleção inteira acima da barra. Nada de chip: a página não anda sem o modo manual.
     */
    @Test
    fun p10_paisagemComGuiadaESelecaoDeixaLinhasAcimaDaBarra() {
        comPaginas {
            comRotacaoManual {
                // O giro é depois de abrir o Leitor: antes disso a tela da frente é o launcher, que fica no retrato.
                val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                regras.usarVoz(VozFalsa.longa(contexto))
                val scenario = abrirLeitorComGuiada(regras, contexto)
                esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                    GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
                }
                esperarPaginaComLinhas(scenario)
                val antesDoGiro = instanciaAtual(scenario)
                device.setOrientationLeft()
                val fimDoGiro = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
                while (device.displayRotation == Surface.ROTATION_0 && System.currentTimeMillis() < fimDoGiro) {
                    Thread.sleep(INTERVALO_DE_LEITURA_MS)
                }
                assertTrue(
                    "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
                    device.displayRotation != Surface.ROTATION_0,
                )
                esperarPaginaRecriada(scenario, antesDoGiro, "paisagem")
                Thread.sleep(PAUSA_APOS_GIRO_MS)
                esperarAte(scenario, "painel da guiada visível na paisagem") { a ->
                    a.findViewById<View>(R.id.layoutGuidedPlayer).visibility == View.VISIBLE
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
                esperarPaginaParada(scenario)
                regras.fecharDialogosDoSistema()
                scenario.onActivity { a ->
                    val chip = a.findViewById<View>(R.id.fabVoltarNarracao).visibility
                    assertEquals("Sem o modo manual não deveria haver chip", View.GONE, chip)
                    assertEquals(View.VISIBLE, a.findViewById<View>(R.id.layoutGuidedPlayer).visibility)
                }

                val (indice, palavra) = esperarPalavraVisivel(scenario, fundoParaEscolherPalavra(scenario))
                selecionarPalavra(scenario, regras, indice, palavra, cenario = "p10")
                regras.fecharDialogosDoSistema()
                esperarAte(scenario, "barra da seleção embaixo na paisagem") { a -> barraEmbaixo(a) }
                Thread.sleep(PAUSA_APOS_SELECAO_MS)

                var medida: MedidaDaPaisagem? = null
                scenario.onActivity { a -> medida = medirPaisagemComSelecao(a, indice) }
                val m = checkNotNull(medida) { "Sem medida da paisagem com seleção" }
                Capturas.anotar("p10", "linhasInteiras=${m.linhasInteiras} linhaDaSelecaoNoTopo=${m.selecaoCabe}")
                Capturas.capturar("84_p10_paisagem_selecao")
                assertTrue(
                    "Deveria caber pelo menos 1 linha inteira acima da barra, couberam ${m.linhasInteiras}",
                    m.linhasInteiras >= 1,
                )
                assertTrue("A linha da seleção deveria ficar inteira acima da barra", m.selecaoCabe)
            }
        }
    }

    /**
     * P8: com a guiada tocando no modo páginas, um arraste lateral vira a página (modo manual) e a guiada é pausada.
     * Um toque longo numa palavra da linha inteira mais baixa que tenha duas palavras elegíveis e um arraste da alça
     * do fim dessa seleção para a direita não rolam a página, e a 1ª linha do topo continua inteira, sem corte em
     * cima (rb-26b, tela pequena).
     */
    @Test
    fun p8_guiadaPausadaComAlcaNaUltimaLinhaNaoRolaAPagina() {
        comPaginas {
            val contexto = InstrumentationRegistry.getInstrumentation().targetContext
            // Voz longa (30 s por trecho): a guiada fica tocando parada no primeiro parágrafo durante o teste.
            regras.usarVoz(VozFalsa.longa(contexto))
            val scenario = abrirLeitorComGuiada(regras, contexto)
            esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
            }
            regras.fecharDialogosDoSistema()
            val antes = medirPagina(scenario)
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "a página virar com o arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
                offsetDaLista(a) > antes.offset
            }
            // Pausa a guiada, como o P5: com ela tocando, a medida do toque e do arraste sai instável.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
            regras.fecharDialogosDoSistema()

            val pagina = medirPagina(scenario)
            // A linha mais baixa que tenha duas palavras elegíveis (o fim de um parágrafo pode ter só uma).
            val linhasInteiras = linhasInteirasDeBaixoParaCima(pagina)
            val escolhida = checkNotNull(
                linhasInteiras.firstNotNullOfOrNull { linha ->
                    duasPalavrasDaLinha(scenario, linha)?.let { Triple(linha, it.first, it.second) }
                },
            ) { "A página não tem linha inteira com duas palavras para o toque" }
            val (ultima, palavraDoToque, palavraDoFim) = escolhida
            if (!ultima.mesmaQue(linhasInteiras.firstOrNull())) {
                Capturas.anotar(
                    "p8",
                    "a linha mais baixa não tinha duas palavras elegíveis; usei a linha ${ultima.numero} " +
                        "do parágrafo ${ultima.paragrafo}",
                )
            }
            Capturas.anotar(
                "p8",
                "virou: offset=${pagina.offset} topo=${pagina.topoVisivel()} ultima=$ultima " +
                    "toque='$palavraDoToque' fim='$palavraDoFim'",
            )
            Capturas.capturar("90_p8_virou_e_pausou")

            selecionarPalavra(scenario, regras, ultima.paragrafo, palavraDoToque, cenario = "p8")
            regras.fecharDialogosDoSistema()
            val selecao = checkNotNull(selecaoAtual(scenario, ultima.paragrafo)) {
                "Sem seleção depois do toque longo em '$palavraDoToque'"
            }
            val depoisDaSelecao = medirPagina(scenario)
            Capturas.anotar(
                "p8",
                "selecao=[${selecao.inicio},${selecao.fim}] offset ${pagina.offset} -> ${depoisDaSelecao.offset}",
            )
            Capturas.capturar("91_p8_selecao_na_ultima_linha")
            assertEquals(
                "Selecionar a palavra da última linha não deveria rolar a página",
                pagina.offset,
                depoisDaSelecao.offset,
            )

            val resultado = arrastarAlcaFinalAte(
                scenario = scenario,
                indiceParagrafo = ultima.paragrafo,
                palavraAlvo = palavraDoFim,
                antes = selecao,
                regras = regras,
                reselecionar = {
                    selecionarPalavra(scenario, regras, ultima.paragrafo, palavraDoToque, cenario = "p8")
                },
                anotar = { Capturas.anotar("p8", it) },
            )
            val depois = resultado.depois ?: throw AssertionError(
                "Arrastar a alça não ampliou a seleção até '$palavraDoFim'. " +
                    "Tentativas: ${resultado.tentativas.joinToString("; ")}",
            )
            val depoisDoArraste = medirPagina(scenario)
            Capturas.anotar(
                "p8",
                "alca: [${depois.inicio},${depois.fim}] texto='${depois.texto}' offset ${depoisDoArraste.offset}",
            )
            Capturas.capturar("92_p8_alca_na_ultima_linha")
            assertTrue(
                "A alça deveria ampliar a seleção até '$palavraDoFim', veio '${depois.texto}'",
                depois.fim > selecao.fim,
            )
            assertTrue(
                "A seleção deveria chegar à palavra '$palavraDoFim', veio '${depois.texto}'",
                depois.texto.trimEnd().endsWith(palavraDoFim),
            )
            assertEquals(
                "Arrastar a alça da última linha não deveria rolar a página",
                pagina.offset,
                depoisDoArraste.offset,
            )
            val primeiraDoTopo = depoisDoArraste.topoVisivel()
            assertTrue(
                "A 1ª linha do topo deveria começar no paddingTop (${depoisDoArraste.topo}) ou depois, " +
                    "veio $primeiraDoTopo",
                primeiraDoTopo != null && primeiraDoTopo.topo >= depoisDoArraste.topo,
            )
            conferirSemLinhaCortada(depoisDoArraste, "p8")
        }
    }

    /**
     * P12 (rb-26b, passo 6): no modo páginas, virar a página com o dedo recolhe o cabeçalho (toolbar, busca e
     * capítulo), e um deslize para baixo o traz de volta. A 1ª linha da página é a mesma antes e depois de mostrar ou
     * recolher o cabeçalho, e um deslize para cima não vira a página.
     */
    @Test
    fun p12_cabecalhoSomeAoVirarEVoltaComDeslizeParaBaixo() {
        comPaginas {
            val scenario = abrirNoPaginado()
            regras.fecharDialogosDoSistema()
            scenario.onActivity { a -> assertEquals(View.VISIBLE, cabecalhoDo(a).visibility) }

            val antes = medirPagina(scenario)
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "a página virar com o arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
                offsetDaLista(a) > antes.offset
            }
            esperarAte(scenario, "cabeçalho recolhido com a virada", TEMPO_DE_VIRADA_MS) { a ->
                cabecalhoDo(a).visibility == View.GONE && !listaDoLeitor(a).isLayoutRequested
            }
            esperarPaginaParada(scenario)
            val recolhido = medirPagina(scenario)
            val topo = checkNotNull(recolhido.topoVisivel()) { "A página recolhida não tem linha no topo" }
            Capturas.anotar("p12", "recolhido: topo=$topo")
            Capturas.capturar("p12_recolhido")
            conferirSemLinhaCortada(recolhido, "p12-recolhido")

            rolarListaComODedo(
                scenario,
                fracaoDaAreaUtil = FRACAO_DO_ARRASTE_VERTICAL,
                paraBaixoNoTexto = false,
                cenario = "p12",
            )
            esperarAte(scenario, "cabeçalho voltou com o deslize para baixo", TEMPO_DE_VIRADA_MS) { a ->
                cabecalhoDo(a).visibility == View.VISIBLE && !listaDoLeitor(a).isLayoutRequested
            }
            esperarPaginaParada(scenario)
            val mostrado = medirPagina(scenario)
            assertTrue(
                "Mostrar o cabeçalho não deveria mudar a 1ª linha (era $topo, veio ${mostrado.topoVisivel()})",
                topo.mesmaQue(mostrado.topoVisivel()),
            )
            conferirSemLinhaCortada(mostrado, "p12-mostrado")
            Capturas.capturar("p12_mostrado")

            rolarListaComODedo(
                scenario,
                fracaoDaAreaUtil = FRACAO_DO_ARRASTE_VERTICAL,
                paraBaixoNoTexto = true,
                cenario = "p12b",
            )
            esperarAte(scenario, "cabeçalho recolhido com o deslize para cima", TEMPO_DE_VIRADA_MS) { a ->
                cabecalhoDo(a).visibility == View.GONE && !listaDoLeitor(a).isLayoutRequested
            }
            esperarPaginaParada(scenario)
            val deNovo = medirPagina(scenario)
            assertTrue(
                "Recolher o cabeçalho não deveria mudar a 1ª linha (era $topo, veio ${deNovo.topoVisivel()})",
                topo.mesmaQue(deNovo.topoVisivel()),
            )
            conferirSemLinhaCortada(deNovo, "p12-de-novo")
            Capturas.capturar("p12_recolhido_de_novo")
        }
    }

    /**
     * P14 (rb-26b, rodada 3): na paisagem, com a guiada no modo páginas, o botão "Voltar à narração" do painel leva a
     * página até a linha narrada, também com a guiada pausada. O parágrafo 0 é longo (50 frases de 2 s): a guiada vira
     * a página dentro dele; depois a pessoa vira duas páginas à mão, pausa e toca no botão. Com a guiada tocando, o
     * mesmo botão leva à linha narrada depois de virar mais duas páginas.
     */
    @Test
    fun p14_paisagemVoltarANarracaoDoPainelLevaALinhaNarrada() {
        comPaginas {
            comRotacaoManual {
                val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                regras.usarVoz(VozFalsa(contexto, 0.0, VOZ_POR_TRECHO_P14_MS, VOZ_POR_TRECHO_P14_MS))
                val livro = listOf(LivroNoAparelho.PARAGRAFO_LONGO) + LivroNoAparelho.PARAGRAFOS
                val scenario = abrirLeitorComGuiada(regras, contexto, textos = livro)
                esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                    GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
                }
                esperarPaginaComLinhas(scenario, livro.size)
                val antesDoGiro = instanciaAtual(scenario)
                device.setOrientationLeft()
                val fimDoGiro = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
                while (device.displayRotation == Surface.ROTATION_0 && System.currentTimeMillis() < fimDoGiro) {
                    Thread.sleep(INTERVALO_DE_LEITURA_MS)
                }
                assertTrue(
                    "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
                    device.displayRotation != Surface.ROTATION_0,
                )
                esperarPaginaRecriada(scenario, antesDoGiro, "paisagem", livro.size)
                Thread.sleep(PAUSA_APOS_GIRO_MS)
                esperarAte(scenario, "painel da guiada visível na paisagem") { a ->
                    a.findViewById<View>(R.id.layoutGuidedPlayer).visibility == View.VISIBLE
                }
                regras.fecharDialogosDoSistema()

                // A guiada vira a página ainda no parágrafo 0: a linha narrada fica além da primeira página dele.
                var offsetAntes = 0
                scenario.onActivity { a -> offsetAntes = offsetDaLista(a) }
                esperarAte(scenario, "a guiada virar a página sozinha no parágrafo 0", TEMPO_DA_GUIADA_MS) { a ->
                    GuidedPlaybackBridge.manager?.state?.value?.index != 0 || offsetDaLista(a) > offsetAntes
                }
                var indiceAoVirar = -1
                var offsetAoVirar = 0
                scenario.onActivity { a ->
                    indiceAoVirar = GuidedPlaybackBridge.manager?.state?.value?.index ?: -1
                    offsetAoVirar = offsetDaLista(a)
                }
                check(indiceAoVirar == 0) {
                    "A guiada passou do parágrafo 0 (índice $indiceAoVirar) antes de virar a página dele"
                }
                assertTrue(
                    "A guiada deveria ter virado a página no parágrafo 0 ($offsetAntes -> $offsetAoVirar)",
                    offsetAoVirar > offsetAntes,
                )

                // Pausada e longe da narração: duas páginas à mão. A linha narrada sai da página.
                InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
                esperarPaginaParada(scenario)
                arrastarLateral(scenario, paraFrente = true)
                esperarPaginaParada(scenario)
                arrastarLateral(scenario, paraFrente = true)
                esperarPaginaParada(scenario)
                esperarAte(scenario, "botão Voltar à narração do painel visível") { a ->
                    a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
                }
                scenario.onActivity { a ->
                    assertEquals(View.GONE, a.findViewById<View>(R.id.fabVoltarNarracao).visibility)
                }
                val foraAntes = lerAtividade(scenario) { narradoNaPagina(it) }
                Capturas.anotar("p14", "pausada, antes do botão: narrado=$foraAntes")
                assertTrue(
                    "O narrado deveria estar fora da página antes do toque, veio $foraAntes",
                    foraAntes?.visivel != true,
                )

                val (x, y) = Gestos.centroDaView(
                    scenario,
                    R.id.btnGuidedVoltarNarracao,
                    "botão Voltar à narração do painel",
                )
                regras.fecharDialogosDoSistema()
                device.click(x, y)
                esperarAte(scenario, "o botão levar a linha narrada inteira à página") { a ->
                    narradoNaPagina(a)?.inteira == true
                }
                Capturas.capturar("p14_pausada_depois_do_botao")
                scenario.onActivity { a ->
                    val rv = listaDoLeitor(a)
                    val inicio = Gestos.textoDoParagrafoOuNulo(a, 0)?.top
                    assertTrue(
                        "O início do parágrafo 0 deveria ficar acima do topo (só foi ao início?), veio $inicio",
                        inicio != null && inicio < rv.paddingTop,
                    )
                    assertEquals(View.GONE, a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility)
                }

                // Tocando: a guiada volta a narrar, a pessoa vira duas páginas e o botão leva de novo à narração.
                InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.resume() }
                esperarAte(scenario, "guiada tocando de novo") { _ ->
                    GuidedPlaybackBridge.manager?.state?.value?.isPlaying == true
                }
                arrastarLateral(scenario, paraFrente = true)
                esperarPaginaParada(scenario)
                arrastarLateral(scenario, paraFrente = true)
                esperarPaginaParada(scenario)
                esperarAte(scenario, "botão Voltar à narração do painel visível de novo") { a ->
                    a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
                }
                val (xDeNovo, yDeNovo) = Gestos.centroDaView(
                    scenario,
                    R.id.btnGuidedVoltarNarracao,
                    "botão Voltar à narração do painel",
                )
                regras.fecharDialogosDoSistema()
                device.click(xDeNovo, yDeNovo)
                esperarAte(scenario, "o botão levar a linha narrada tocando à página") { a ->
                    narradoNaPagina(a)?.inteira == true
                }
                Capturas.capturar("p14_tocando_depois_do_botao")
            }
        }
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
     * Abre o livro de teste no Só ler, já no modo páginas. Espera a lista ter os parágrafos, a paginação ativa e o topo
     * alinhado numa linha inteira.
     */
    private fun abrirNoPaginado(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)
        esperarAte(scenario, "páginas na tela") { a ->
            val lista = listaDoLeitor(a)
            lista.adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size &&
                lista.paginacao?.ativa == true &&
                lista.findViewHolderForAdapterPosition(0) != null &&
                RegraDasPaginasDoLeitor.ajusteDoTopo(
                    lista.linhasNaTela(),
                    lista.paddingTop,
                    lista.height - lista.paddingBottom - lista.paddingTop,
                ) == 0
        }
        return scenario
    }

    /** Lista do Leitor, na thread principal. */
    private fun listaDoLeitor(a: ReaderActivity): RecyclerViewParagrafos =
        a.findViewById(R.id.recyclerViewParagrafos)

    /** Deslocamento vertical da lista (o que o modo páginas muda ao virar), na thread principal. */
    private fun offsetDaLista(a: ReaderActivity): Int = listaDoLeitor(a).computeVerticalScrollOffset()

    /** O cabeçalho do Leitor (toolbar, busca e capítulo), que a tela cheia recolhe ao virar (rb-26b, passo 6). */
    private fun cabecalhoDo(a: ReaderActivity): View = a.findViewById(R.id.cabecalhoDoLeitor)

    /** Mede a página agora (lista, corte e linhas) numa passagem pela thread principal. */
    private fun medirPagina(scenario: ActivityScenario<ReaderActivity>): EstadoDaPagina {
        var estado: EstadoDaPagina? = null
        scenario.onActivity { a -> estado = paginaDe(a) }
        return checkNotNull(estado) { "Sem medida da página do Leitor" }
    }

    /** A página de [a], na thread principal. Falha se a lista não estiver em modo páginas. */
    private fun paginaDe(a: ReaderActivity): EstadoDaPagina {
        val lista = listaDoLeitor(a)
        val paginacao = checkNotNull(lista.paginacao) { "A lista do Leitor não tem paginação" }
        return EstadoDaPagina(
            offset = lista.computeVerticalScrollOffset(),
            topo = lista.paddingTop,
            fundo = lista.height - lista.paddingBottom,
            corte = paginacao.corte(),
            linhas = linhasDaLista(lista),
            vertical = (lista.layoutManager as? LinearLayoutManager)?.orientation == LinearLayoutManager.VERTICAL,
            ativa = paginacao.ativa,
        )
    }

    /** Linhas de todos os parágrafos na tela, nas coordenadas da lista, com o parágrafo e o número da linha. */
    private fun linhasDaLista(lista: RecyclerViewParagrafos): List<LinhaNaPagina> {
        val linhas = mutableListOf<LinhaNaPagina>()
        for (i in 0 until lista.childCount) {
            val holder = lista.getChildViewHolder(lista.getChildAt(i)) as? ParagrafoAdapter.VHLeitura ?: continue
            val tv = holder.tv
            val layout = tv.layout ?: continue
            val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
            for (numero in 0 until layout.lineCount) {
                linhas += LinhaNaPagina(
                    paragrafo = holder.bindingAdapterPosition,
                    numero = numero,
                    topo = y0 + layout.getLineTop(numero),
                    base = y0 + layout.getLineBottom(numero),
                )
            }
        }
        return linhas.sortedBy { it.topo }
    }

    /** As linhas inteiras da página (acima do corte e abaixo do topo), da mais baixa para a mais alta. */
    private fun linhasInteirasDeBaixoParaCima(pagina: EstadoDaPagina): List<LinhaNaPagina> =
        pagina.linhas
            .filter { it.topo >= pagina.topo && it.base <= pagina.corte }
            .sortedByDescending { it.base }

    /**
     * Duas palavras (de 4 letras ou mais) inteiras dentro da [linha], nessa ordem, cuja primeira ocorrência no
     * parágrafo é a que está na linha. O toque e a alça do teste acham a palavra pela primeira ocorrência (ver
     * [Gestos.pontoDaPalavra]), então uma palavra repetida antes na página não serve. Nulo se não há duas.
     */
    private fun duasPalavrasDaLinha(
        scenario: ActivityScenario<ReaderActivity>,
        linha: LinhaNaPagina,
    ): Pair<String, String>? {
        var achadas: Pair<String, String>? = null
        scenario.onActivity { a ->
            val tv = Gestos.textoDoParagrafo(a, linha.paragrafo)
            val layout = checkNotNull(tv.layout) { "Parágrafo ${linha.paragrafo} sem layout" }
            val texto = tv.text.toString()
            val inicioDaLinha = layout.getLineStart(linha.numero)
            val fimDaLinha = layout.getLineEnd(linha.numero)
            val palavras = Regex("\\p{L}{4,}").findAll(texto, inicioDaLinha)
                .takeWhile { it.range.first < fimDaLinha }
                .filter { it.range.last < fimDaLinha && texto.indexOf(it.value) == it.range.first }
                .map { it.value }
                .toList()
            achadas = palavras.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
        }
        return achadas
    }

    /**
     * Conferência do P1: nenhuma linha visível cruza o corte. Uma linha que começa acima do corte tem de terminar nele
     * ou antes; a linha parcial de baixo fica escondida.
     */
    private fun conferirSemLinhaCortada(pagina: EstadoDaPagina, cenario: String) {
        val cortadas = pagina.linhas.filter { it.topo < pagina.corte && it.base > pagina.corte }
        Capturas.anotar(cenario, "linhas cruzando o corte ${pagina.corte}: ${cortadas.size}")
        assertTrue(
            "Nenhuma linha deveria cruzar o corte ${pagina.corte}, mas cruzam: $cortadas",
            cortadas.isEmpty(),
        )
    }

    /**
     * Arraste lateral de quase toda a largura da lista, começando perto do centro (fora das bordas do gesto de voltar)
     * e sem mudar de altura. [paraFrente] é o dedo da direita para a esquerda, que vira a página para a frente.
     */
    private fun arrastarLateral(scenario: ActivityScenario<ReaderActivity>, paraFrente: Boolean) {
        val area = Rect()
        var densidade = 1f
        scenario.onActivity { a ->
            listaDoLeitor(a).getGlobalVisibleRect(area)
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

    /** Parágrafo narrado pela guiada e onde a sua linha de progresso cai na página (rb-26b). Na thread principal. */
    private fun narradoNaPagina(a: ReaderActivity): Narrado? {
        val manager = GuidedPlaybackBridge.manager ?: return null
        val indice = manager.state.value.index
        val lista = listaDoLeitor(a)
        val tv = Gestos.textoDoParagrafoOuNulo(a, indice) ?: return null
        // Com a guiada pausada o estado já zerou o progresso: o manager guarda a linha que estava sendo narrada.
        val linha = linhaDoProgresso(tv, manager.progressoDoNarrado) ?: return null
        val fundo = lista.height - lista.paddingBottom
        val visivel = linha.base > lista.paddingTop && linha.topo < fundo
        val inteira = linha.topo >= lista.paddingTop && linha.base <= checkNotNull(lista.paginacao).corte()
        return Narrado(indice, linha.topo, linha.base, visivel, inteira)
    }

    /** Espera a lista do Leitor ter parágrafos montados, a paginação ativa e linhas na tela. */
    private fun esperarPaginaComLinhas(
        scenario: ActivityScenario<ReaderActivity>,
        totalDeParagrafos: Int = LivroNoAparelho.PARAGRAFOS.size,
    ) {
        esperarAte(scenario, "páginas com linhas na tela") { a ->
            val lista = listaDoLeitor(a)
            lista.adapter?.itemCount == totalDeParagrafos &&
                lista.paginacao?.ativa == true &&
                lista.linhasNaTela().isNotEmpty()
        }
    }

    /** Espera o deslocamento da página parar: cinco leituras iguais seguidas, a cada 200 ms (ou o tempo limite). */
    private fun esperarPaginaParada(scenario: ActivityScenario<ReaderActivity>) {
        var anterior = -1
        var iguais = 0
        val fim = System.currentTimeMillis() + TEMPO_MAXIMO_PAGINA_PARADA_MS
        while (System.currentTimeMillis() < fim) {
            val atual = lerAtividade(scenario) { offsetDaLista(it) } ?: -1
            iguais = if (atual == anterior) iguais + 1 else 0
            anterior = atual
            if (iguais >= LEITURAS_IGUAIS_PARA_PARAR) return
            Thread.sleep(INTERVALO_DE_PAGINA_PARADA_MS)
        }
    }

    /** Se a barra da seleção está embaixo agora: visível, com a gravidade de baixo (rb-26b, passo 3). */
    private fun barraEmbaixo(a: ReaderActivity): Boolean {
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val gravidade = (barra.layoutParams as? FrameLayout.LayoutParams)?.gravity
        return barra.visibility == View.VISIBLE && gravidade == Gravity.BOTTOM
    }

    /** Topo da barra da seleção na tela, ou nulo com a barra escondida. */
    private fun topoDaBarraNaTela(a: ReaderActivity): Int? {
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        return if (barra.visibility == View.VISIBLE) topoNaTela(barra) else null
    }

    private fun barraSumiu(scenario: ActivityScenario<ReaderActivity>): Boolean =
        lerAtividade(scenario) { a -> a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.GONE } == true

    /** Espera a barra da seleção sumir (até [tempoMs]). Devolve se ela sumiu. */
    private fun esperarBarraSumir(scenario: ActivityScenario<ReaderActivity>, tempoMs: Long): Boolean {
        val fim = System.currentTimeMillis() + tempoMs
        while (System.currentTimeMillis() < fim) {
            if (barraSumiu(scenario)) return true
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        return false
    }

    /** Topo da view na tela, em pixels (com a translação). */
    private fun topoNaTela(view: View): Int = IntArray(2).also { view.getLocationOnScreen(it) }[1]

    /**
     * Medida da paisagem com a seleção (P10), na thread principal. [linhasInteiras] conta as linhas inteiras entre o
     * topo útil da lista e o menor entre o corte e o topo da barra. [selecaoCabe] diz se a linha da seleção (do
     * layout do TextView, na tela) fica inteira entre o topo útil e o topo da barra.
     */
    private fun medirPaisagemComSelecao(a: ReaderActivity, indice: Int): MedidaDaPaisagem {
        val lista = listaDoLeitor(a)
        val topoLista = topoNaTela(lista)
        val topoUtil = lista.paddingTop
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val topoBarra = topoNaTela(barra) - topoLista
        val fundoUtil = minOf(checkNotNull(lista.paginacao).corte(), topoBarra)
        val inteiras = linhasDaLista(lista).count { it.topo >= topoUtil && it.base <= fundoUtil }

        val tv = Gestos.textoDoParagrafo(a, indice)
        val layout = checkNotNull(tv.layout) { "A seleção não tem layout" }
        val linha = layout.getLineForOffset(minOf(tv.selectionStart, tv.selectionEnd))
        val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
        val topoDaSelecao = y0 + layout.getLineTop(linha)
        val baseDaSelecao = y0 + layout.getLineBottom(linha)
        val cabe = topoDaSelecao >= topoUtil && baseDaSelecao <= topoBarra
        return MedidaDaPaisagem(inteiras, cabe)
    }

    /** Medida da paisagem com a seleção (P10): linhas inteiras acima da barra e se a linha da seleção cabe. */
    private data class MedidaDaPaisagem(val linhasInteiras: Int, val selecaoCabe: Boolean)

    /**
     * Roda [leitura] na Activity atual, na thread do teste. Logo depois de um giro, o ActivityScenario pode estar com
     * a instância antiga destruída, ou com a nova ainda sem resumir: aí onActivity lança IllegalStateException, e a
     * função devolve nulo para quem chama tentar de novo.
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

    /** Identidade (hash) da Activity do Leitor agora, para ver depois se ela foi recriada pelo giro. */
    private fun instanciaAtual(scenario: ActivityScenario<ReaderActivity>): Int {
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        while (System.currentTimeMillis() < fim) {
            lerAtividade(scenario) { System.identityHashCode(it) }?.let { return it }
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        throw AssertionError("Não consegui ler a Activity do Leitor em ${TEMPO_LIMITE_ESPERA_MS / 1000} s")
    }

    /**
     * Espera a Activity nova (outra identidade que [antes]) com o modo páginas ativo e o topo alinhado numa linha inteira.
     * Falha com o estado visto se não chegar a tempo.
     */
    private fun esperarPaginaRecriada(
        scenario: ActivityScenario<ReaderActivity>,
        antes: Int,
        momento: String,
        totalDeParagrafos: Int = LivroNoAparelho.PARAGRAFOS.size,
    ) {
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        var visto = "nenhuma Activity disponível"
        while (System.currentTimeMillis() < fim) {
            val pronto = lerAtividade(scenario) { a ->
                val lista = listaDoLeitor(a)
                val nova = System.identityHashCode(a) != antes
                val ativa = lista.paginacao?.ativa == true
                val altura = lista.height - lista.paddingBottom - lista.paddingTop
                val alinhada = RegraDasPaginasDoLeitor.ajusteDoTopo(lista.linhasNaTela(), lista.paddingTop, altura) == 0
                // Sem linha na tela (lista sem layout ou sem parágrafos montados), ajusteDoTopo devolve 0 por falta de
                // linhas. A virada pode ter tirado o parágrafo 0 da tela, então o que conta é haver linha visível.
                val montada = lista.adapter?.itemCount == totalDeParagrafos &&
                    lista.linhasNaTela().isNotEmpty()
                visto = "nova=$nova ativa=$ativa alinhada=$alinhada montada=$montada"
                nova && ativa && alinhada && montada
            }
            if (pronto == true) return
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        throw AssertionError("Girei a tela para a $momento e o Leitor não voltou com a página alinhada: $visto")
    }

    /**
     * Desliga a rotação automática antes de [corpo] e, no fim (mesmo se falhar), volta à orientação natural e restaura
     * o ajuste de rotação automática que estava antes. Mesma regra do CapitulosNoAparelhoTest.
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

    /** Uma linha de um parágrafo na tela: o parágrafo, o número dela no texto e as bordas na lista (rb-26b). */
    private data class LinhaNaPagina(val paragrafo: Int, val numero: Int, val topo: Int, val base: Int) {
        fun mesmaQue(outra: LinhaNaPagina?): Boolean =
            outra != null && paragrafo == outra.paragrafo && numero == outra.numero
    }

    /** Estado da página: deslocamento, topo e fundo úteis, corte, as linhas e a orientação da lista. */
    private class EstadoDaPagina(
        val offset: Int,
        val topo: Int,
        val fundo: Int,
        val corte: Int,
        val linhas: List<LinhaNaPagina>,
        val vertical: Boolean,
        val ativa: Boolean,
    ) {
        /** A 1ª linha que não cabe na página (a que a próxima virada traz para o topo). */
        fun primeiraEscondida(): LinhaNaPagina? = linhas.firstOrNull { it.base > fundo }

        /** A linha mais alta que ainda aparece na página. */
        fun topoVisivel(): LinhaNaPagina? = linhas.filter { it.base > topo }.minByOrNull { it.topo }
    }

    /** Parágrafo narrado: a linha da narração na lista e se ela aparece na página. */
    private data class Narrado(
        val indice: Int,
        val topo: Int,
        val base: Int,
        val visivel: Boolean,
        /** A linha narrada inteira na página: acima do corte e abaixo do topo útil. */
        val inteira: Boolean,
    )

    private companion object {
        const val PALAVRA = "chuva"
        const val PALAVRA_ADIANTE = "manhã"

        /** Fração da largura da lista que cada arraste lateral anda para cada lado do centro (0,34 no total). */
        const val FRACAO_DO_ARRASTE_LATERAL = 0.17f

        /** Distância mínima do arraste lateral, em dp, para contar como virada (ver GestoArraste). */
        const val DISTANCIA_MINIMA_DP = 120f

        /** Fração da área útil que o arraste vertical do P3 anda. */
        const val FRACAO_DO_ARRASTE_VERTICAL = 0.5f

        /** Tempo para uma virada feita pelo dedo ou pela tecla aparecer na lista. */
        const val TEMPO_DE_VIRADA_MS = 5_000L

        /** Tempo para a guiada, tocando, passar do corte e virar a página sozinha. */
        const val TEMPO_DA_GUIADA_MS = 90_000L

        /** Duração de cada trecho da voz falsa no P5, em ms. */
        const val VOZ_POR_TRECHO_P5_MS = 6_000

        /** Duração de cada frase da voz falsa no P14, em ms: o parágrafo longo leva ~100 s para ser narrado. */
        const val VOZ_POR_TRECHO_P14_MS = 2_000

        /** Pausa depois do giro, para o layout e o alinhamento da página assentarem antes de medir. */
        const val PAUSA_APOS_GIRO_MS = 500L

        const val INTERVALO_DE_LEITURA_MS = 100L

        /** Até quando esperar a página parar antes da medida, com a guiada pausada ou tocando. */
        const val TEMPO_MAXIMO_PAGINA_PARADA_MS = 15_000L

        /** Leituras iguais seguidas que dão a página por parada. */
        const val LEITURAS_IGUAIS_PARA_PARAR = 5

        /** Intervalo entre as leituras do deslocamento da página, em ms. */
        const val INTERVALO_DE_PAGINA_PARADA_MS = 200L

        /** Pausa depois de selecionar, para a barra e o layout assentarem antes de medir. */
        const val PAUSA_APOS_SELECAO_MS = 400L
    }
}
