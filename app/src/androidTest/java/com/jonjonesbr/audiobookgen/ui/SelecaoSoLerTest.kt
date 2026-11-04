package com.jonjonesbr.audiobookgen.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
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
 * Seleção de texto no "Só ler" no emulador (rb-46 U3): o toque longo seleciona uma palavra, a alça do fim amplia
 * a seleção, e a barra de ações cabe na tela com a fonte maior. Os toques são reais (ver [Gestos]); as conferências
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

    /**
     * Topo da linha do início da seleção e fundo da barra, em coordenadas da tela (rb-48). O retângulo da barra é só
     * para as medidas.
     */
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
     * Toque longo na palavra do parágrafo 0 e espera a barra da seleção aparecer com a seleção no TextView. Falha com
     * a mensagem da espera se a seleção não abrir.
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
     * Mede, na thread principal, a linha do início da seleção do parágrafo 0 e a base da barra, ambas em
     * coordenadas da tela. Nulo sem seleção ou sem layout. Mesma conta de linhaDoInicioDaSelecaoNaTela (rb-48).
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

    /** Espera até 2 s o início da seleção do parágrafo 0 ficar menor que [inicioAntes]; nulo se ele não andar. */
    private fun esperarInicioMenorQue(scenario: ActivityScenario<ReaderActivity>, inicioAntes: Int): Selecao? {
        val fim = System.currentTimeMillis() + TEMPO_PARA_SELECAO_MUDAR_MS
        var atual = selecaoAtual(scenario)
        while (atual == null || atual.inicio >= inicioAntes) {
            if (System.currentTimeMillis() >= fim) return null
            Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
            atual = selecaoAtual(scenario)
        }
        return atual
    }

    /**
     * Diagnóstico (rb-46diag), só na 1ª tentativa do C03: posições da alça e do fim, o TextView do parágrafo 0
     * na tela e as janelas que o UiAutomation enxerga.
     */
    private fun anotarDiagnosticoAntesDoArraste(
        scenario: ActivityScenario<ReaderActivity>,
        alca: PointF,
        fim: PointF,
    ) {
        Capturas.anotar("c03", "alca=(${alca.x.toInt()},${alca.y.toInt()}) fim=(${fim.x.toInt()},${fim.y.toInt()})")
        var textView = ""
        scenario.onActivity { a ->
            val tv = Gestos.textoDoParagrafo(a, 0)
            val posicao = IntArray(2)
            tv.getLocationOnScreen(posicao)
            textView = "textview0 location=(${posicao[0]},${posicao[1]}) paddingTop=${tv.totalPaddingTop} " +
                "scrollY=${tv.scrollY} altura=${tv.height}"
        }
        Capturas.anotar("c03", textView)
        anotarJanelasDaTela()
    }

    /**
     * Anota as janelas que o UiAutomation enxerga (tipo, camada, título e retângulo na tela). Liga
     * FLAG_RETRIEVE_INTERACTIVE_WINDOWS só durante a leitura e devolve a flag como estava, porque a UiAutomation é
     * compartilhada pelos testes seguintes do processo. Se a flag ou a leitura falharem, só anota o erro.
     */
    private fun anotarJanelasDaTela() {
        val automacao = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flagsAntes = runCatching { automacao.serviceInfo?.flags }.getOrNull()
        runCatching {
            val info = automacao.serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automacao.serviceInfo = info
            }
        }.onFailure { Capturas.anotar("c03", "flag de janelas não configurada: ${it.message}") }
        try {
            runCatching {
                val janelas = automacao.windows.orEmpty()
                Capturas.anotar("c03", "janelas=${janelas.size}")
                janelas.forEach { janela ->
                    val retangulo = Rect()
                    janela.getBoundsInScreen(retangulo)
                    Capturas.anotar(
                        "c03",
                        "janela tipo=${janela.type} layer=${janela.layer} titulo=${janela.title} " +
                            "bounds=${retangulo.toShortString()}"
                    )
                }
            }.onFailure { Capturas.anotar("c03", "janelas indisponíveis: ${it.message}") }
        } finally {
            if (flagsAntes != null) {
                runCatching {
                    automacao.serviceInfo?.let { info ->
                        info.flags = flagsAntes
                        automacao.serviceInfo = info
                    }
                }
            }
        }
    }

    /**
     * Diagnóstico (rb-46diag), só na 1ª tentativa do C03: anota a seleção em cada passo marcado do arraste, com o
     * tempo desde o início do arraste (ms), para comparar com as tentativas sem captura.
     */
    private fun observarPassoDoArraste(
        scenario: ActivityScenario<ReaderActivity>,
        inicioDoArraste: Long,
        passo: Int,
        x: Float,
        y: Float,
    ) {
        if (passo !in PASSOS_ANOTADOS) return
        val decorrido = SystemClock.uptimeMillis() - inicioDoArraste
        val selecao = selecaoAtual(scenario)
        Capturas.anotar(
            "c03-passo",
            "passo=$passo ms=$decorrido dedo=(${x.toInt()},${y.toInt()}) selecao=${selecao ?: "nenhuma"}"
        )
        if (passo == PASSO_DA_CAPTURA_DO_MEIO) Capturas.capturar("07a_soler_alca_meio")
    }

    /**
     * Diagnóstico (rb-46diag), só na 1ª tentativa do C03: anota a seleção e o tempo desde o início do arraste, e
     * captura a tela antes de soltar.
     */
    private fun observarAntesDeSoltar(scenario: ActivityScenario<ReaderActivity>, inicioDoArraste: Long) {
        val decorrido = SystemClock.uptimeMillis() - inicioDoArraste
        Capturas.anotar(
            "c03-passo",
            "antes_de_soltar ms=$decorrido selecao=${selecaoAtual(scenario) ?: "nenhuma"}"
        )
        Capturas.capturar("07b_soler_alca_antes_de_soltar")
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
     * C03: só ler, arrastar a alça do fim da seleção amplia a seleção até uma palavra mais adiante. O arraste é
     * relativo (rb-48), como o de uma pessoa: o dedo anda o quanto a alça tem de andar, do ponto da alça até o alvo
     * deslocado pelo ponto do fim da seleção ([Gestos.pontoDoFimDaSelecao]). Soltar o dedo direto no alvo não
     * funciona, porque o Android mantém a distância entre o dedo e a ponta da alça.
     *
     * Tenta a alça a 12, 8 e 16 dp abaixo da linha e, em cada uma, uma folga extra de 0 e de 0,4 linha somada ao
     * deslocamento vertical. Para no primeiro arraste que amplia a seleção. Cada tentativa vai para as capturas, e a
     * falha lista todas.
     */
    @Test
    fun c03_arrastarAlcaAmplia() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, PALAVRA)
        val antes = checkNotNull(selecaoAtual(scenario)) { "Sem seleção antes do arraste" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c03", "antes=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.capturar("06_soler_alca_antes")

        val tentativas = mutableListOf<String>()
        var depois: Selecao? = null
        var sucesso: String? = null
        val combinacoes = DESLOCAMENTOS_DA_ALCA_DP.flatMap { abaixo -> FOLGAS_EXTRAS_DA_LINHA.map { abaixo to it } }
        for ((indice, combinacao) in combinacoes.withIndex()) {
            val (abaixoDp, folga) = combinacao
            // Diagnóstico (rb-46diag): só a primeira tentativa (12 dp, folga 0) anota cada passo e tira capturas.
            val diagnostico = indice == 0
            // Uma tentativa que falhou pode ter mudado a seleção (antes virava 'c'): se não é a inicial, reselecionar.
            val atual = selecaoAtual(scenario)
            if (atual == null || atual.inicio != antes.inicio || atual.fim != antes.fim) {
                selecionarPalavra(scenario, PALAVRA)
            }
            // A lista pode ter descido (rb-48): alvo, alça e fim são medidos de novo em cada tentativa.
            val alvo = Gestos.pontoDaPalavra(scenario, 0, PALAVRA_ADIANTE)
            val alca = Gestos.pontoDaAlcaFinal(scenario, 0, abaixoDp)
            val fim = Gestos.pontoDoFimDaSelecao(scenario, 0)
            val altura = Gestos.alturaDaLinha(scenario, 0)
            val dx = alvo.x - fim.x
            val dy = alvo.y - fim.y
            val extra = folga * altura
            regras.fecharDialogosDoSistema()
            if (diagnostico) anotarDiagnosticoAntesDoArraste(scenario, alca, fim)
            val inicioDoArraste = SystemClock.uptimeMillis()
            val observarPasso: (Int, Float, Float) -> Unit = { passo, x, y ->
                observarPassoDoArraste(scenario, inicioDoArraste, passo, x, y)
            }
            val antesDeSoltar: () -> Unit = { observarAntesDeSoltar(scenario, inicioDoArraste) }
            Gestos.arrastar(
                alca.x,
                alca.y,
                alca.x + dx,
                alca.y + dy + extra,
                aoMover = if (diagnostico) observarPasso else null,
                antesDeSoltar = if (diagnostico) antesDeSoltar else null,
            )
            val duracaoDoGesto = SystemClock.uptimeMillis() - inicioDoArraste
            val achado = esperarFimMaiorQue(scenario, antes.fim)
            val descricao = "abaixo=${abaixoDp}dp folga=$folga dx=${dx.toInt()} dy=${dy.toInt()} " +
                "extra=${extra.toInt()}px gesto=${duracaoDoGesto}ms seleção=${achado ?: selecaoAtual(scenario)}"
            tentativas += descricao
            Capturas.anotar("c03", "tentativa: $descricao")
            if (achado != null) {
                depois = achado
                sucesso = descricao
                break
            }
        }

        val resultado = depois
        if (resultado == null) {
            val mensagem = "Arrastar a alça não ampliou a seleção: fim antes=${antes.fim} " +
                "texto='${antes.texto}', alvo '$PALAVRA_ADIANTE', última seleção=${selecaoAtual(scenario)}, " +
                "tentativas: " + tentativas.joinToString("; ")
            Capturas.capturar("07_soler_alca_depois")
            throw AssertionError(mensagem)
        }
        Capturas.anotar(
            "c03",
            "deu certo em [$sucesso] depois=[${resultado.inicio},${resultado.fim}] texto='${resultado.texto}'"
        )
        Capturas.capturar("07_soler_alca_depois")
    }

    /**
     * C03b (rb-46diag): arrasta a alça INICIAL da seleção de 'chuva' para a esquerda, na mesma linha (dy = 0), até
     * o 'A' anterior. Separa dois casos: se a injeção de toque não pega alça nenhuma, o início não anda; se só a
     * troca de linha da alça final falha, esta alça (mesma linha) tem de andar. Anota as medidas e captura a tela
     * antes de conferir.
     */
    @Test
    fun c03b_arrastarAlcaInicialParaEsquerda() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, PALAVRA)
        val antes = checkNotNull(selecaoAtual(scenario)) { "Sem seleção antes do arraste" }
        regras.fecharDialogosDoSistema()
        val alca = Gestos.pontoDaAlcaInicial(scenario, 0)
        val inicio = Gestos.pontoDoInicioDaSelecao(scenario, 0)
        // O meio do 'A' (offset 26) fica a uma letra e um espaço à esquerda do início de 'chuva' (offset 28), na
        // mesma linha. Com o meio da letra, o alvo não cai na fronteira entre 'A' e o espaço.
        val alvo = Gestos.pontoDoMeioDaPrimeiraLetra(scenario, 0, PALAVRA_ANTERIOR)
        val dx = alvo.x - inicio.x
        Capturas.anotar("c03b", "antes=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.anotar(
            "c03b",
            "alca=(${alca.x.toInt()},${alca.y.toInt()}) inicio=(${inicio.x.toInt()},${inicio.y.toInt()}) " +
                "alvo=(${alvo.x.toInt()},${alvo.y.toInt()}) dx=${dx.toInt()} dy=0"
        )
        regras.fecharDialogosDoSistema()
        Gestos.arrastar(alca.x, alca.y, alca.x + dx, alca.y)
        val depois = esperarInicioMenorQue(scenario, antes.inicio)
        Capturas.anotar("c03b", "depois=${depois ?: selecaoAtual(scenario)}")
        Capturas.capturar("09_soler_alca_inicial")
        assertTrue(
            "O início da seleção deveria ficar antes de ${antes.inicio} (entrar '$PALAVRA_ANTERIOR'), " +
                "veio ${depois ?: selecaoAtual(scenario)}",
            depois != null && depois.inicio < antes.inicio
        )
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
                        "visivelNaTela=${rBotao.width()}px altura=${botao.height}px " +
                        "visivelNaAltura=${rBotao.height()}px"
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

        /** Palavra logo antes de 'chuva' (com o espaço, para achar só o 'A' do início do parágrafo 0). */
        const val PALAVRA_ANTERIOR = "A "

        /** Passos do arraste da primeira tentativa do C03 em que a seleção é anotada (diagnóstico rb-46diag). */
        val PASSOS_ANOTADOS = setOf(1, 5, 10, 15, 20)

        /** Passo do arraste em que a tela é capturada no meio do gesto (diagnóstico rb-46diag). */
        const val PASSO_DA_CAPTURA_DO_MEIO = 10

        /** Distâncias abaixo da linha do fim (em dp) em que a alça é arrastada, na ordem das tentativas. */
        val DESLOCAMENTOS_DA_ALCA_DP = listOf(12f, 8f, 16f)

        /** Folgas extras do arraste, em fração da altura da linha, somadas ao deslocamento vertical do dedo. */
        val FOLGAS_EXTRAS_DA_LINHA = listOf(0f, 0.4f)

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
