package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.view.Surface
import android.view.View
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
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
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
                virarPaginaComGuiadaEmManual(scenario)
                val (indice, palavra) = esperarPalavraVisivel(scenario)
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
                comPaisagem {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    esperarGuiadaTocando(scenario, 0)
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
                comPaisagem {
                    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
                    regras.usarVoz(VozFalsa.longa(contexto))
                    val scenario = abrirLeitorComGuiada(regras, contexto)
                    virarPaginaComGuiadaEmManual(scenario)
                    val (indice, palavra) = esperarPalavraVisivel(scenario)
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
                comPaisagem {
                    val scenario = abrirSoLerPaginado()
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
     * sem a página andando), vira uma página com um arraste lateral e espera o chip "Voltar à narração" aparecer.
     */
    private fun virarPaginaComGuiadaEmManual(scenario: ActivityScenario<ReaderActivity>) {
        esperarGuiadaTocando(scenario, 0)
        esperarPaginaComLinhas(scenario)
        regras.fecharDialogosDoSistema()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { GuidedPlaybackBridge.manager?.pause() }
        val antes = medir(scenario).offset
        arrastarLateral(scenario, paraFrente = true)
        esperarAte(scenario, "a página virar com o arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
            a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos).computeVerticalScrollOffset() > antes
        }
        esperarAte(scenario, "chip 'Voltar à narração' visível depois do arraste lateral", TEMPO_DE_VIRADA_MS) { a ->
            a.findViewById<View>(R.id.fabVoltarNarracao).visibility == View.VISIBLE
        }
        regras.fecharDialogosDoSistema()
    }

    /** Espera uma palavra inteira (5 letras ou mais) numa linha inteira da página; devolve o parágrafo e a palavra. */
    private fun esperarPalavraVisivel(scenario: ActivityScenario<ReaderActivity>): Pair<Int, String> {
        var achada: Pair<Int, String>? = null
        esperarAte(scenario, "uma palavra inteira visível na página") { a ->
            achada = palavraVisivelDaPagina(a)
            achada != null
        }
        return achada ?: throw AssertionError("Não achei palavra inteira visível na página")
    }

    /** Primeira palavra de 5 letras em linha inteira dentro da página, na thread principal; nula se não há. */
    private fun palavraVisivelDaPagina(a: ReaderActivity): Pair<Int, String>? {
        val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
        val fundo = lista.height - lista.paddingBottom
        for (i in 0 until lista.childCount) {
            val holder = lista.getChildViewHolder(lista.getChildAt(i)) as? ParagrafoAdapter.VHLeitura ?: continue
            val tv = holder.tv
            val layout = tv.layout ?: continue
            val y0 = tv.top + tv.totalPaddingTop - tv.scrollY
            for (numero in 0 until layout.lineCount) {
                val topo = y0 + layout.getLineTop(numero)
                val base = y0 + layout.getLineBottom(numero)
                if (topo < lista.paddingTop || base > fundo) continue
                val trecho = tv.text.subSequence(layout.getLineStart(numero), layout.getLineEnd(numero)).toString()
                val palavra = PALAVRA_INTEIRA.find(trecho)?.value ?: continue
                return holder.bindingAdapterPosition to palavra
            }
        }
        return null
    }

    /** Espera a guiada tocando o parágrafo [indice] (estado lido direto do manager, sem onView). */
    private fun esperarGuiadaTocando(scenario: ActivityScenario<ReaderActivity>, indice: Int) {
        esperarAte(scenario, "leitura guiada tocando o parágrafo $indice") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == indice } == true
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
     * limite de cima (topo útil da lista, ou a base da barra da seleção se ela está no topo) e o de baixo (o corte, o
     * topo do painel da guiada, o topo do chip "Voltar à narração" e o topo da barra da seleção se ela está embaixo).
     */
    private fun medirTelaPequena(a: ReaderActivity): Medida {
        val lista = a.findViewById<RecyclerViewParagrafos>(R.id.recyclerViewParagrafos)
        val paginacao = checkNotNull(lista.paginacao) { "A lista do Leitor não tem paginação" }
        val corte = paginacao.corte()
        val topoLista = topoNaTela(lista)
        val meio = a.resources.displayMetrics.heightPixels / 2

        var limiteDeCima = topoLista + lista.paddingTop
        var limiteDeBaixo = topoLista + corte

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
     * Gira para a paisagem antes de [corpo] (rotação manual, para o Leitor abrir já na paisagem) e espera a rotação
     * assentar. A volta ao retrato e a rotação automática ficam em [comRotacaoManual].
     */
    private fun comPaisagem(corpo: () -> Unit) {
        comRotacaoManual {
            device.setOrientationLeft()
            val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
            while (device.displayRotation == Surface.ROTATION_0 && System.currentTimeMillis() < fim) {
                Thread.sleep(INTERVALO_DE_ROTACAO_MS)
            }
            if (device.displayRotation == Surface.ROTATION_0) {
                throw AssertionError("Pedi a paisagem, mas a rotação ficou em ${device.displayRotation}")
            }
            Thread.sleep(PAUSA_APOS_GIRO_MS)
            corpo()
        }
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
        /** Palavra inteira de 5 letras ou mais (para o toque longo cair numa palavra só). */
        val PALAVRA_INTEIRA = Regex("\\p{L}{5,}")

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
    }
}
