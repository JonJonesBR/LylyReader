package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import com.jonjonesbr.audiobookgen.util.dpToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Barras recolhidas no aparelho (rb-66, passo 4). No vertical, com a barra de cima em TEMPO, a setinha aparece no alto,
 * o toque mostra a barra, ela recolhe sozinha depois de uns segundos sem tocar, e rolar um pouco para cima com o dedo
 * a mostra de novo.
 *
 * R1: vertical, TEMPO. As capturas saem em `r1_*`, uma por estado.
 *
 * Painel de baixo (rb-66, passo 5). R2: retrato com a guiada, painel em TEMPO: a tira (≤ 72 dp), o play da tira pausa
 * e o ícone acompanha, a seta abre, e sem tocar o painel recolhe. R3: paisagem, com a tira (play e seta inteiros).
 *
 * Critério de aceite (rb-66, passo 8). R4: retrato, modo páginas, com a tira na base: a linha da seleção fica inteira
 * acima da barra da seleção. R5: o giro com o cabeçalho aberto: no MANUAL ele continua aberto, no TEMPO recolhe.
 *
 * rb-67, item 6: R6, paisagem com o cabeçalho recolhido: a barra de status some e volta com o cabeçalho. Item 18b: R7,
 * o R4 com a fonte 1,5.
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

    /** R2: retrato, guiada tocando, painel de baixo em TEMPO. Capturas em `r2_*`. */
    @Test
    fun r2_retratoGuiadaTiraPausaAbreERecolhe() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = false
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        AppPrefs(contexto).readerBarraDeBaixo = "TEMPO"
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)

        // (1) Painel recolhido: a tira, com o play e a seta, em no máximo 72 dp.
        esperarAte(scenario, "painel da guiada recolhido na tira") { a -> tiraVisivel(a) }
        scenario.onActivity { a ->
            val painel = a.findViewById<View>(R.id.layoutGuidedPlayer)
            assertTrue("a tira mede ${painel.height}px, acima de 72 dp", painel.height <= a.dpToPx(72))
        }
        esperarAte(scenario, "guiada tocando antes do toque no play da tira") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.isPlaying == true
        }
        Capturas.capturar("r2_1_tira")

        // (2) O play da tira pausa a guiada, e o ícone e a descrição acompanham.
        val (xPlay, yPlay) = Gestos.centroDaView(scenario, R.id.btnGuidedPlayPauseTira, "play da tira")
        Gestos.toqueCurto(xPlay.toFloat(), yPlay.toFloat())
        esperarAte(scenario, "o play da tira pausa a guiada") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.isPlaying == false
        }
        scenario.onActivity { a ->
            assertEquals(
                a.getString(R.string.cd_guided_play),
                a.findViewById<View>(R.id.btnGuidedPlayPauseTira).contentDescription.toString(),
            )
        }
        Capturas.capturar("r2_2_tira_pausada")

        // (3) A seta abre o painel inteiro.
        val (xSeta, ySeta) = Gestos.centroDaView(scenario, R.id.btnGuidedRecolher, "seta do painel")
        Gestos.toqueCurto(xSeta.toFloat(), ySeta.toFloat())
        esperarAte(scenario, "a seta abre os controles do painel") { a -> controlesAbertos(a) }
        scenario.onActivity { a ->
            assertEquals(
                a.getString(R.string.cd_guided_recolher_controles),
                a.findViewById<View>(R.id.btnGuidedRecolher).contentDescription.toString(),
            )
        }
        Capturas.capturar("r2_3_aberto")

        // (4) Sem tocar, o painel volta a ser tira (uns 4 s; a guiada pausada não segura o tempo).
        esperarAte(scenario, "painel recolhe sozinho sem interação", timeoutMs = 15_000L) { a ->
            !controlesAbertos(a)
        }
        Capturas.capturar("r2_4_recolhido_pelo_tempo")
    }

    /**
     * R3: paisagem com a guiada, no modo páginas: a tira mostra o play e a seta inteiros, e o "Voltar à narração" (com
     * a narração para trás, depois de duas páginas) cabe no rótulo. Capturas em `r3_*`.
     */
    @Test
    fun r3_paisagemTiraComPlayESetaInteiros() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        AppPrefs(contexto).readerBarraDeBaixo = "TEMPO"
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarAte(scenario, "painel da guiada recolhido na tira") { a -> tiraVisivel(a) }

        val automaticaAntes = girarParaPaisagem()
        try {
            esperarAte(scenario, "paisagem com a tira do painel") { a ->
                a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && tiraVisivel(a)
            }
            virarDuasPaginasComTecla(scenario)
            esperarAte(scenario, "Voltar à narração visível com a narração para trás") { a ->
                a.findViewById<View>(R.id.btnGuidedVoltarNarracao).visibility == View.VISIBLE
            }
            Capturas.capturar("r3_1_paisagem_tira")
            conferirTiraNaPaisagem(scenario)
        } finally {
            devolverRotacao(automaticaAntes)
        }
    }

    /**
     * R4 (rb-66, passo 8): retrato, modo páginas, com a tira da guiada na base (TEMPO nas duas barras). A guiada é
     * pausada (como no P8), com a fonte 1,3, e um toque longo na palavra da última linha inteira da página abre a
     * seleção. A linha selecionada tem de ficar inteira acima do topo da barra da seleção: a barra cobre o lugar da
     * tira, não o texto. Capturas em `r4_*`.
     */
    @Test
    fun r4_retratoSelecaoNaUltimaLinhaFicaAcimaDaBarra() {
        // A fonte 1,3 é a condição de referência das medidas de S1 a S5.
        conferirSelecaoNaUltimaLinhaAcimaDaBarra(fonte = "1.3", prefixo = "r4")
    }

    /**
     * R7 (rb-67, item 18b): o mesmo caso do R4, com a fonte 1,5. A barra da seleção é mais alta que a tira, e a
     * página a reserva: a linha selecionada continua inteira acima dela, e a página não muda quando a seleção abre.
     * Capturas em `r7_*`.
     */
    @Test
    fun r7_fonteGrandeSelecaoNaUltimaLinhaFicaAcimaDaBarra() {
        conferirSelecaoNaUltimaLinhaAcimaDaBarra(fonte = "1.5", prefixo = "r7")
    }

    /**
     * Retrato, modo páginas, tira na base, com a fonte [fonte] (escala do sistema): seleciona a última palavra inteira
     * da página e confere que a linha da seleção fica inteira acima da barra da seleção. Capturas em `[prefixo]_*`.
     */
    private fun conferirSelecaoNaUltimaLinhaAcimaDaBarra(fonte: String, prefixo: String) {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        AppPrefs(contexto).readerBarraDeBaixo = "TEMPO"
        // A regra restaura a escala depois do teste.
        regras.comandoDoShell("settings put system font_scale $fonte")
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
        }
        esperarAte(scenario, "barra de cima e tira da guiada recolhidas") { a ->
            a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.GONE && tiraVisivel(a)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
        regras.fecharDialogosDoSistema()
        esperarPaginaParada(scenario)
        Capturas.capturar("${prefixo}_1_tira_na_base")

        val (paragrafo, palavra) = ultimaPalavraDaUltimaLinhaInteira(scenario)
        selecionarPalavra(scenario, regras, paragrafo, palavra, cenario = prefixo)
        regras.fecharDialogosDoSistema()
        var medida: LinhaDaSelecao? = null
        scenario.onActivity { a -> medida = medirLinhaDaSelecao(a, paragrafo) }
        val linha = checkNotNull(medida) { "Sem a linha da seleção da palavra '$palavra'" }
        Capturas.anotar(
            prefixo,
            "linha da seleção=[${linha.topo},${linha.base}] topoUtil=${linha.topoUtil} " +
                "barra=${linha.barraTopo} barraVisivel=${linha.barraVisivel}",
        )
        Capturas.capturar("${prefixo}_2_selecao_na_ultima_linha")
        assertTrue("A barra da seleção deveria estar à vista", linha.barraVisivel)
        assertTrue(
            "A linha selecionada deveria ficar inteira acima da barra (topo ${linha.barraTopo} px), " +
                "veio [${linha.topo}, ${linha.base}]",
            linha.base <= linha.barraTopo && linha.topo >= linha.topoUtil,
        )
    }

    /**
     * R5 (rb-66, passo 8), MANUAL: no modo páginas, o cabeçalho aberto com o deslize para baixo continua aberto depois
     * do giro para a paisagem (o MANUAL guarda esse estado no giro) e não recolhe sozinho. Capturas em `r5_1_*` e
     * `r5_2_*`.
     */
    @Test
    fun r5_manualGiroMantemOCabecalhoAberto() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        AppPrefs(contexto).readerBarraDeCima = "MANUAL"
        val scenario = abrirSoLerPaginado(contexto)
        esperarPaginaComLinhas(scenario)
        regras.fecharDialogosDoSistema()
        scenario.onActivity { a ->
            val visibilidade = a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility
            assertEquals("No MANUAL o cabeçalho começa recolhido", View.GONE, visibilidade)
        }
        rolarListaComODedo(scenario, fracaoDaAreaUtil = FRACAO_DO_DESLIZE, paraBaixoNoTexto = false, cenario = "r5")
        esperarAte(scenario, "cabeçalho aberto pelo deslize para baixo") { a -> cabecalhoVisivel(a) }
        Capturas.capturar("r5_1_manual_aberto")

        val automaticaAntes = girarParaPaisagem()
        try {
            esperarAte(scenario, "paisagem com o cabeçalho ainda aberto") { a ->
                a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && cabecalhoVisivel(a)
            }
            // Mais que o tempo do TEMPO (uns 4 s): o MANUAL não pode recolher sozinho.
            Thread.sleep(ESPERA_DE_CONFERENCIA_MS)
            scenario.onActivity { a ->
                assertTrue("No MANUAL o cabeçalho deveria continuar aberto depois do giro", cabecalhoVisivel(a))
            }
            Capturas.capturar("r5_2_manual_girado")
        } finally {
            devolverRotacao(automaticaAntes)
        }
    }

    /**
     * R5 (rb-66, passo 8), TEMPO: o cabeçalho aberto com o deslize para baixo volta recolhido depois do giro para a
     * paisagem (o Leitor novo começa com a barra de TEMPO recolhida). Capturas em `r5_3_*` e `r5_4_*`.
     */
    @Test
    fun r5_tempoGiroRecolheOCabecalho() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        val scenario = abrirSoLerPaginado(contexto)
        esperarPaginaComLinhas(scenario)
        regras.fecharDialogosDoSistema()
        rolarListaComODedo(scenario, fracaoDaAreaUtil = FRACAO_DO_DESLIZE, paraBaixoNoTexto = false, cenario = "r5b")
        esperarAte(scenario, "cabeçalho aberto pelo deslize para baixo") { a -> cabecalhoVisivel(a) }
        Capturas.capturar("r5_3_tempo_aberto")
        // O TEMPO recolhe uns 4 s sem toque: confere que o cabeçalho ainda está aberto logo antes do giro. Sem isso, o
        // teste passaria mesmo se o giro não recolhesse nada.
        scenario.onActivity { a ->
            assertTrue("Antes do giro o cabeçalho deveria estar aberto (TEMPO)", cabecalhoVisivel(a))
        }

        val automaticaAntes = girarParaPaisagem()
        try {
            esperarAte(scenario, "paisagem com o cabeçalho recolhido depois do giro", TEMPO_DE_RECOLHER_MS) { a ->
                a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !cabecalhoVisivel(a)
            }
            Capturas.capturar("r5_4_tempo_girado_recolhido")
        } finally {
            devolverRotacao(automaticaAntes)
        }
    }

    /**
     * R6 (rb-67, item 6), TEMPO: no modo páginas, o retrato mostra a barra de status com o cabeçalho recolhido; na
     * paisagem recolhida ela some e o texto sobe (o inset de cima fica em 0); o deslize para baixo mostra o cabeçalho e
     * a barra junto. De volta ao retrato, a barra volta, e um menu (⋮) aberto e fechado não a esconde. Capturas em
     * `r6_*`.
     */
    @Test
    fun r6_paisagemCabecalhoRecolhidoEscondeABarraDeStatus() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerRolagemHorizontal = true
        AppPrefs(contexto).readerBarraDeCima = "TEMPO"
        val scenario = abrirSoLerPaginado(contexto)
        esperarPaginaComLinhas(scenario)
        regras.fecharDialogosDoSistema()
        esperarAte(scenario, "retrato com o cabeçalho recolhido mostra a barra de status") { a ->
            !cabecalhoVisivel(a) && barraDeStatusVisivel(a)
        }

        val automaticaAntes = girarParaPaisagem()
        try {
            esperarAte(scenario, "paisagem com o cabeçalho recolhido") { a ->
                a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !cabecalhoVisivel(a)
            }
            esperarAte(scenario, "paisagem recolhida esconde a barra de status") { a -> !barraDeStatusVisivel(a) }
            // Sem a barra de status o texto sobe: o inset de cima do sistema fica em 0.
            scenario.onActivity { a ->
                val topo = topoDoInsetDoSistema(a)
                assertEquals("com a barra de status escondida, o inset de cima deveria ser 0", 0, topo)
            }
            anotarLinhasInteiras(scenario, "r6_linhas_paisagem_recolhida")
            Capturas.capturar("r6_1_paisagem_recolhida")

            rolarListaComODedo(scenario, fracaoDaAreaUtil = FRACAO_DO_DESLIZE, paraBaixoNoTexto = false, cenario = "r6")
            esperarAte(scenario, "deslize para baixo mostra o cabeçalho e a barra de status") { a ->
                cabecalhoVisivel(a) && barraDeStatusVisivel(a)
            }
            Capturas.capturar("r6_2_paisagem_aberta")
        } finally {
            devolverRotacao(automaticaAntes)
        }

        // De volta ao retrato, a barra de status volta. Depois de um menu aberto e fechado, ela continua à vista: o
        // listener de foco da virada não pode esconder a barra no retrato.
        esperarAte(scenario, "retrato de volta com a barra de status") { a -> barraDeStatusVisivel(a) }
        rolarListaComODedo(scenario, fracaoDaAreaUtil = FRACAO_DO_DESLIZE, paraBaixoNoTexto = false, cenario = "r6_ret")
        esperarAte(scenario, "retrato com o cabeçalho aberto pelo deslize") { a -> cabecalhoVisivel(a) }
        abrirEFecharMenuDeOpcoes(scenario)
        esperarAte(scenario, "barra de status à vista no retrato depois do menu") { a -> barraDeStatusVisivel(a) }
        Capturas.capturar("r6_3_retrato_depois_do_menu")
    }

    /** A barra de status está à vista na janela do Leitor (pelos insets da janela). */
    private fun barraDeStatusVisivel(a: ReaderActivity): Boolean =
        ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true

    /** Altura do topo do sistema pelos insets da janela: a barra de status, ou 0 quando ela está escondida. */
    private fun topoDoInsetDoSistema(a: ReaderActivity): Int =
        ViewCompat.getRootWindowInsets(a.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: -1

    /** Abre o menu ⋮ do Leitor e fecha com Voltar: o menu tira o foco da janela e o devolve ao fechar. */
    private fun abrirEFecharMenuDeOpcoes(scenario: ActivityScenario<ReaderActivity>) {
        val (x, y) = Gestos.centroDaView(scenario, R.id.btnReaderOpcoes, "menu ⋮ do Leitor")
        Gestos.toqueCurto(x.toFloat(), y.toFloat())
        Thread.sleep(ESPERA_DO_MENU_MS)
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        instrumentacao.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentacao.waitForIdleSync()
        esperarAte(scenario, "janela do Leitor de volta ao foco depois do menu") { a -> a.hasWindowFocus() }
        // Dá tempo para o listener de foco da janela rodar (se houver algum que esconda a barra, ele roda aqui).
        Thread.sleep(ESPERA_DO_FOCO_MS)
    }

    /** Anota em medidas.txt quantas linhas inteiras da página cabem na tela. */
    private fun anotarLinhasInteiras(scenario: ActivityScenario<ReaderActivity>, nome: String) {
        var linhas = -1
        scenario.onActivity { a ->
            val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
            val corte = checkNotNull(lista.paginacao) { "A lista do Leitor não tem paginação" }.corte()
            var inteiras = 0
            for (i in 0 until lista.childCount) {
                val holder = lista.getChildViewHolder(lista.getChildAt(i)) as? ParagrafoAdapter.VHLeitura ?: continue
                val tv = holder.tv
                val layout = tv.layout ?: continue
                val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
                for (numero in 0 until layout.lineCount) {
                    val topo = y0 + layout.getLineTop(numero)
                    val base = y0 + layout.getLineBottom(numero)
                    if (topo >= lista.paddingTop && base <= corte) inteiras++
                }
            }
            linhas = inteiras
        }
        Capturas.anotar(nome, "linhas inteiras: $linhas")
    }

    /** Abre o livro de teste no Só ler (sem a leitura guiada), no modo que o corpo do teste já gravou. */
    private fun abrirSoLerPaginado(contexto: Context): ActivityScenario<ReaderActivity> {
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        return regras.abrir(intent)
    }

    /** Espera a paginação da lista do Leitor ativa e com linhas na tela. */
    private fun esperarPaginaComLinhas(scenario: ActivityScenario<ReaderActivity>) {
        esperarAte(scenario, "páginas com linhas na tela") { a ->
            val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
            lista.paginacao?.ativa == true && lista.linhasNaTela().isNotEmpty()
        }
    }

    /** Espera o deslocamento da página parar: cinco leituras iguais seguidas, a cada 200 ms (ou o tempo limite). */
    private fun esperarPaginaParada(scenario: ActivityScenario<ReaderActivity>) {
        var anterior = -1
        var iguais = 0
        val fim = System.currentTimeMillis() + TEMPO_MAXIMO_PAGINA_PARADA_MS
        while (System.currentTimeMillis() < fim) {
            var atual = -1
            scenario.onActivity { a ->
                val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
                atual = lista.computeVerticalScrollOffset()
            }
            iguais = if (atual == anterior) iguais + 1 else 0
            anterior = atual
            if (iguais >= LEITURAS_IGUAIS_PARA_PARAR) return
            Thread.sleep(INTERVALO_DE_PAGINA_PARADA_MS)
        }
    }

    /**
     * Parágrafo e palavra da última linha inteira da página (acima do corte e abaixo do topo útil). A palavra tem 5
     * letras ou mais e é a primeira ocorrência dela no parágrafo, para o toque longo acertar a palavra certa.
     */
    private fun ultimaPalavraDaUltimaLinhaInteira(scenario: ActivityScenario<ReaderActivity>): Pair<Int, String> {
        var achada: Pair<Int, String>? = null
        scenario.onActivity { a ->
            val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
            val corte = checkNotNull(lista.paginacao) { "A lista do Leitor não tem paginação" }.corte()
            var baseMaisBaixa = -1
            for (i in 0 until lista.childCount) {
                val holder = lista.getChildViewHolder(lista.getChildAt(i)) as? ParagrafoAdapter.VHLeitura ?: continue
                val tv = holder.tv
                val layout = tv.layout ?: continue
                val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
                for (numero in 0 until layout.lineCount) {
                    val topo = y0 + layout.getLineTop(numero)
                    val base = y0 + layout.getLineBottom(numero)
                    if (topo < lista.paddingTop || base > corte || base <= baseMaisBaixa) continue
                    val palavra = palavraDaLinha(tv, layout.getLineStart(numero), layout.getLineEnd(numero))
                    if (palavra != null) {
                        baseMaisBaixa = base
                        achada = holder.bindingAdapterPosition to palavra
                    }
                }
            }
        }
        return checkNotNull(achada) { "A página não tem linha inteira com uma palavra para o toque" }
    }

    /** A primeira palavra de 5 letras ou mais, inteira na linha [inicio, fim) do texto, se é a 1ª ocorrência nele. */
    private fun palavraDaLinha(tv: TextView, inicio: Int, fim: Int): String? {
        val texto = tv.text.toString()
        return PALAVRA_DE_CINCO_LETRAS.findAll(texto, inicio)
            .takeWhile { it.range.first < fim }
            .firstOrNull { it.range.last < fim && texto.indexOf(it.value) == it.range.first }
            ?.value
    }

    /** A linha da seleção do parágrafo [paragrafo] e a barra da seleção, na tela. Nula sem layout do texto. */
    private fun medirLinhaDaSelecao(a: ReaderActivity, paragrafo: Int): LinhaDaSelecao? {
        val tv = Gestos.textoDoParagrafo(a, paragrafo)
        val layout = tv.layout ?: return null
        val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val linha = layout.getLineForOffset(minOf(tv.selectionStart, tv.selectionEnd))
        val y0 = topoNaTela(tv) + tv.totalPaddingTop - tv.scrollY
        return LinhaDaSelecao(
            topo = y0 + layout.getLineTop(linha),
            base = y0 + layout.getLineBottom(linha),
            barraTopo = topoNaTela(barra),
            barraVisivel = barra.visibility == View.VISIBLE,
            topoUtil = topoNaTela(lista) + lista.paddingTop,
        )
    }

    /** Posições na tela, em pixels, da linha da seleção, da barra da seleção e do topo útil da lista (R4). */
    private class LinhaDaSelecao(
        val topo: Int,
        val base: Int,
        val barraTopo: Int,
        val barraVisivel: Boolean,
        val topoUtil: Int,
    )

    /** O cabeçalho do Leitor está à vista. */
    private fun cabecalhoVisivel(a: ReaderActivity): Boolean =
        a.findViewById<View>(R.id.cabecalhoDoLeitor).visibility == View.VISIBLE

    /** Topo da view na tela, em pixels (com a translação). */
    private fun topoNaTela(view: View): Int = IntArray(2).also { view.getLocationOnScreen(it) }[1]

    /**
     * Duas teclas PAGE_DOWN, para a narração ficar para trás (como nos testes P). Sai-se do modo de toque antes, e
     * volta-se a ele no fim, mesmo se o teste falhar: o primeiro PAGE_DOWN no modo de toque não chega à Activity.
     */
    private fun virarDuasPaginasComTecla(scenario: ActivityScenario<ReaderActivity>) {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        instrumentacao.setInTouchMode(false)
        instrumentacao.waitForIdleSync()
        try {
            esperarAte(scenario, "sair do modo de toque") { a -> !a.window.decorView.isInTouchMode }
            repeat(2) {
                instrumentacao.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
                instrumentacao.waitForIdleSync()
            }
        } finally {
            instrumentacao.setInTouchMode(true)
        }
    }

    /**
     * Medidas da tira na paisagem: altura, play e seta inteiros, e o rótulo do Voltar à narração sem reticências (ele
     * fica visível, porque a narração ficou para trás).
     */
    private fun conferirTiraNaPaisagem(scenario: ActivityScenario<ReaderActivity>) {
        scenario.onActivity { a ->
            val painel = a.findViewById<View>(R.id.layoutGuidedPlayer)
            assertTrue("a tira da paisagem mede ${painel.height}px, acima de 72 dp", painel.height <= a.dpToPx(72))
            val play = a.findViewById<View>(R.id.btnGuidedPlayPause)
            val seta = a.findViewById<View>(R.id.btnGuidedRecolher)
            assertTrue("o play da tira não está inteiro (${play.width}px)", play.width >= a.dpToPx(MINIMO_BOTAO_DP))
            assertTrue("a seta da tira não está inteira (${seta.width}px)", seta.width >= a.dpToPx(MINIMO_BOTAO_DP))
            val voltar = a.findViewById<View>(R.id.btnGuidedVoltarNarracao)
            assertEquals("o Voltar à narração deveria estar à vista", View.VISIBLE, voltar.visibility)
            val reticencias = reticenciasDo(voltar as TextView)
            assertEquals("o rótulo do Voltar à narração ficou com reticências", 0, reticencias)
        }
    }

    /** A tira está à vista: o painel tem altura, os controles (voz, anterior...) estão escondidos e a seta aparece. */
    private fun tiraVisivel(a: ReaderActivity): Boolean {
        val painel = a.findViewById<View>(R.id.layoutGuidedPlayer)
        return painel.visibility == View.VISIBLE && painel.height > 0 && !controlesAbertos(a) &&
            a.findViewById<View>(R.id.btnGuidedRecolher).isShown
    }

    /**
     * Os controles (voz, anterior, próximo, fechar) estão à vista. Usa `isShown`, não `visibility`: na paisagem eles
     * são irmãos da tira, e na paisagem e no retrato o pai (`controlesGuiada`) pode estar escondido com o filho
     * VISIBLE.
     */
    private fun controlesAbertos(a: ReaderActivity): Boolean = a.findViewById<View>(R.id.btnGuidedVoz).isShown

    /**
     * Gira para a paisagem com o Leitor aberto, com a rotação automática desligada. Devolve o valor antigo da
     * preferência, para [devolverRotacao] desfazer no fim do teste.
     */
    private fun girarParaPaisagem(): String {
        val automaticaAntes = regras.comandoDoShell("settings get system accelerometer_rotation").trim()
        regras.comandoDoShell("settings put system accelerometer_rotation 0")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.setOrientationLeft()
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        while (device.displayRotation == Surface.ROTATION_0 && System.currentTimeMillis() < fim) {
            Thread.sleep(INTERVALO_DE_ROTACAO_MS)
        }
        assertTrue(
            "Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}",
            device.displayRotation != Surface.ROTATION_0,
        )
        return automaticaAntes
    }

    /** Devolve o aparelho ao retrato e restaura a rotação automática como estava antes do teste. */
    private fun devolverRotacao(automaticaAntes: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        runCatching {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
        if (automaticaAntes.isEmpty() || automaticaAntes == "null") {
            regras.comandoDoShell("settings delete system accelerometer_rotation")
        } else {
            regras.comandoDoShell("settings put system accelerometer_rotation $automaticaAntes")
        }
    }

    /** Quantas linhas do texto foram cortadas com reticências. Zero quando o rótulo cabe inteiro. */
    private fun reticenciasDo(texto: TextView): Int {
        val layout = texto.layout ?: return 0
        return (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
    }

    private companion object {
        const val MINIMO_BOTAO_DP = 44
        const val INTERVALO_DE_ROTACAO_MS = 200L

        /** Esperas do menu ⋮ no R6: abrir o menu, e depois de fechá-lo até o foco da janela voltar e assentar. */
        const val ESPERA_DO_MENU_MS = 800L
        const val ESPERA_DO_FOCO_MS = 1_000L

        /** Fração da área útil que o deslize para baixo do R5 anda (o cabeçalho aparece com ele). */
        const val FRACAO_DO_DESLIZE = 0.5f

        /** Tempo para o TEMPO recolher o cabeçalho depois do giro (R5). */
        const val TEMPO_DE_RECOLHER_MS = 15_000L

        /** Quanto esperar, depois do giro, para mostrar que o MANUAL não recolhe sozinho (o TEMPO é de uns 4 s). */
        const val ESPERA_DE_CONFERENCIA_MS = 6_000L

        /** Até quando esperar a página parar (R4), e as leituras iguais seguidas que dão a página por parada. */
        const val TEMPO_MAXIMO_PAGINA_PARADA_MS = 15_000L
        const val LEITURAS_IGUAIS_PARA_PARAR = 5
        const val INTERVALO_DE_PAGINA_PARADA_MS = 200L

        /** Palavra de 5 letras ou mais (para o toque longo cair numa palavra só, como no SelecaoDeTexto). */
        val PALAVRA_DE_CINCO_LETRAS = Regex("\\p{L}{5,}")
    }
}
