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
 * viram a página; P5 é a leitura guiada virando sozinha e o chip "Voltar à narração"; P6 gira a tela; P7 confere
 * PAGE_DOWN; P9 e P10 são a seleção com a barra embaixo, no retrato e na paisagem (rb-26b, passo 3). Os toques são
 * reais (ver [Gestos]); as conferências leem as Views na thread principal.
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
     * deslocamento aumenta) e o parágrafo narrado continua na tela. Um arraste lateral pelo leitor liga o chip "Voltar
     * à narração", e tocar nele leva à página do narrado.
     */
    @Test
    fun p5_guiadaViraAPaginaEOChipVoltaAoNarrado() {
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
            // do arraste sai instável. pause() mantém o índice narrado, então o chip ainda consegue levar até ele.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }

            // O leitor vira a página com o dedo: a guiada entra em modo manual e o chip aparece.
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "chip 'Voltar à narração' visível depois do arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
                a.findViewById<View>(R.id.fabVoltarNarracao).visibility == View.VISIBLE
            }
            Capturas.capturar("77_p5_chip_voltar")
            // Antes do toque, o narrado tem de estar fora da página: é isso que o chip sinaliza. Nulo quer dizer que o
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
                Capturas.anotar("p5", "antes do chip: leitura da tela falhou (${erro.message})")
            }
            Capturas.anotar("p5", "antes do chip: narrado=$foraAntes lido=$lido")
            assertTrue("Não consegui ler a tela antes do chip", lido)
            assertTrue(
                "O narrado deveria estar fora da página antes do toque no chip (nulo = parágrafo fora da lista montada), veio $foraAntes",
                foraAntes?.visivel != true,
            )

            val (x, y) = Gestos.centroDaView(scenario, R.id.fabVoltarNarracao, "chip Voltar à narração")
            regras.fecharDialogosDoSistema()
            device.click(x, y)
            esperarAte(scenario, "o chip levar à página do parágrafo narrado", TEMPO_DE_VIRADA_MS) { a ->
                narradoNaPagina(a)?.visivel == true
            }
            val depoisDoChip = lerAtividade(scenario) { narradoNaPagina(it) }
            Capturas.anotar("p5", "depois do chip: narrado=$depoisDoChip")
            Capturas.capturar("78_p5_depois_do_chip")
            assertTrue(
                "O toque no chip deveria levar o narrado de volta à página, veio $depoisDoChip",
                depoisDoChip?.visivel == true,
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
            // Vira uma página à mão (modo manual, com o chip), como no S2: o texto para e a seleção é feita sobre ele.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
            arrastarLateral(scenario, paraFrente = true)
            esperarAte(scenario, "chip 'Voltar à narração' visível depois do arraste", TEMPO_DE_VIRADA_MS) { a ->
                a.findViewById<View>(R.id.fabVoltarNarracao).visibility == View.VISIBLE
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
                device.setOrientationLeft()
                Thread.sleep(PAUSA_APOS_GIRO_MS)
                assertTrue(
                    "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
                    device.displayRotation != Surface.ROTATION_0,
                )
                val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                regras.usarVoz(VozFalsa.longa(contexto))
                val scenario = abrirLeitorComGuiada(regras, contexto)
                esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
                    GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
                }
                esperarPaginaComLinhas(scenario)
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
        val estado = GuidedPlaybackBridge.manager?.state?.value ?: return null
        val lista = listaDoLeitor(a)
        val tv = Gestos.textoDoParagrafoOuNulo(a, estado.index) ?: return null
        val linha = linhaDoProgresso(tv, estado.progressPct) ?: return null
        val fundo = lista.height - lista.paddingBottom
        val visivel = linha.base > lista.paddingTop && linha.topo < fundo
        return Narrado(estado.index, linha.topo, linha.base, visivel)
    }

    /** Espera a lista do Leitor ter parágrafos montados, a paginação ativa e linhas na tela. */
    private fun esperarPaginaComLinhas(scenario: ActivityScenario<ReaderActivity>) {
        esperarAte(scenario, "páginas com linhas na tela") { a ->
            val lista = listaDoLeitor(a)
            lista.adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size &&
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
    private fun esperarPaginaRecriada(scenario: ActivityScenario<ReaderActivity>, antes: Int, momento: String) {
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
                val montada = lista.adapter?.itemCount == LivroNoAparelho.PARAGRAFOS.size &&
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
    private data class Narrado(val indice: Int, val topo: Int, val base: Int, val visivel: Boolean)

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
