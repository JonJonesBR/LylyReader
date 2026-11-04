package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spannable
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.DiagnosticoDoArraste
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.JanelasDaTela
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.arrastarAlcaFinalAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.esperarInicioMenorQue
import com.jonjonesbr.audiobookgen.ui.apoio.esperarLinhaAbaixoDaBarra
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val janelas = JanelasDaTela.ler { Capturas.anotar("c03", it) }
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
        val selecao = selecaoAtual(scenario, 0)
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
            "antes_de_soltar ms=$decorrido selecao=${selecaoAtual(scenario, 0) ?: "nenhuma"}"
        )
        Capturas.capturar("07b_soler_alca_antes_de_soltar")
    }

    private fun visibilidade(a: ReaderActivity, id: Int): Int = a.findViewById<View>(id).visibility

    /** Se o parágrafo [indice] tem view na lista agora (rb-58: o parágrafo saiu ou voltou à tela). */
    private fun paragrafoNaTela(scenario: ActivityScenario<ReaderActivity>, indice: Int): Boolean {
        var naTela = false
        scenario.onActivity { a ->
            val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            naTela = (0 until lista.childCount).any {
                lista.getChildViewHolder(lista.getChildAt(it))?.bindingAdapterPosition == indice
            }
        }
        return naTela
    }

    /** Quantos spans do trecho guardado (rb-58) há no parágrafo [indice] de [a]. Sem onActivity aninhado. */
    private fun spansDoTrechoNaTela(a: ReaderActivity, indice: Int): Int {
        val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
        val tv = (lista.findViewHolderForAdapterPosition(indice) as? ParagrafoAdapter.VHLeitura)?.tv
        val texto = tv?.text as? Spannable ?: return 0
        return texto.getSpans(0, texto.length, SpanDoTrechoGuardado::class.java).size
    }

    /** Spans do trecho guardado (rb-58) no TextView do parágrafo [indice], como pares (início, fim). */
    private fun faixasDoTrechoGuardado(scenario: ActivityScenario<ReaderActivity>, indice: Int): List<Pair<Int, Int>> {
        var faixas: List<Pair<Int, Int>> = emptyList()
        scenario.onActivity { a ->
            val texto = Gestos.textoDoParagrafo(a, indice).text as? Spannable ?: return@onActivity
            faixas = texto.getSpans(0, texto.length, SpanDoTrechoGuardado::class.java).map {
                texto.getSpanStart(it) to texto.getSpanEnd(it)
            }
        }
        return faixas
    }

    /** C02: só ler, o toque longo seleciona a palavra e a barra mostra só Destacar e Definir. */
    @Test
    fun c02_toqueLongoSelecionaPalavra() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, regras, 0, PALAVRA)
        val selecao = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção depois do toque longo em '$PALAVRA'" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c02", "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}'")
        // rb-48: a linha da palavra selecionada tem de ficar abaixo do fundo da barra (a lista desce se preciso).
        val medida = esperarLinhaAbaixoDaBarra(scenario, 0)
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
     * C16 (rb-58): no Só ler, seleciona uma palavra do parágrafo 0 e rola com o dedo até ele sair da tela. A barra
     * fica acesa com Destacar e Definir, agindo sobre o trecho guardado. Rolando de volta, o trecho aparece pintado
     * na faixa da palavra. Voltar limpa o trecho, a barra some e o Leitor segue aberto.
     */
    @Test
    fun c16_trechoGuardadoAoSairDaTelaEVoltar() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, regras, 0, PALAVRA, cenario = "c16")
        val selecao = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção depois do toque longo em '$PALAVRA'" }
        regras.fecharDialogosDoSistema()

        var rolagens = 0
        while (paragrafoNaTela(scenario, 0) && rolagens < MAXIMO_DE_ROLAGENS_C16) {
            rolarListaComODedo(scenario, FRACAO_DA_ROLAGEM_C16, paraBaixoNoTexto = true, cenario = "c16")
            rolagens++
        }
        assertFalse("O parágrafo 0 não saiu da tela em $rolagens rolagens", paragrafoNaTela(scenario, 0))
        Thread.sleep(PAUSA_DEPOIS_DA_ROLAGEM_C16_MS)
        regras.fecharDialogosDoSistema()
        Capturas.capturar("29_c16_guardado_fora_da_tela")
        scenario.onActivity { a ->
            assertEquals(
                "A barra deveria ficar acesa com o trecho guardado",
                View.VISIBLE,
                visibilidade(a, R.id.barraSelecaoTexto),
            )
            assertEquals("Destacar na barra do Só ler", View.VISIBLE, visibilidade(a, R.id.btnSelecaoTextoDestacar))
            assertEquals("Definir na barra do Só ler", View.VISIBLE, visibilidade(a, R.id.btnSelecaoTextoDefinir))
        }

        rolagens = 0
        while (!paragrafoNaTela(scenario, 0) && rolagens < MAXIMO_DE_ROLAGENS_C16) {
            rolarListaComODedo(scenario, FRACAO_DA_ROLAGEM_C16, paraBaixoNoTexto = false, cenario = "c16")
            rolagens++
        }
        assertTrue("O parágrafo 0 não voltou à tela em $rolagens rolagens", paragrafoNaTela(scenario, 0))
        Thread.sleep(PAUSA_DEPOIS_DA_ROLAGEM_C16_MS)
        regras.fecharDialogosDoSistema()
        val faixas = faixasDoTrechoGuardado(scenario, 0)
        Capturas.anotar("c16", "faixas do trecho guardado=$faixas seleção=[${selecao.inicio},${selecao.fim}]")
        Capturas.capturar("30_c16_guardado_de_volta")
        assertEquals(
            "O trecho guardado deveria aparecer pintado na faixa de '$PALAVRA' ao voltar",
            listOf(selecao.inicio to selecao.fim),
            faixas,
        )

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        esperarAte(scenario, "Voltar limpa o trecho guardado e esconde a barra") { a ->
            visibilidade(a, R.id.barraSelecaoTexto) != View.VISIBLE && spansDoTrechoNaTela(a, 0) == 0
        }
        Capturas.capturar("31_c16_voltar_limpa")
        scenario.onActivity { a -> assertFalse("O Voltar não deveria fechar o Leitor", a.isFinishing) }
    }

    /**
     * C03: só ler, arrastar a alça do fim da seleção amplia a seleção até uma palavra mais adiante. O laço de
     * tentativas (alça a 16, 12 e 8 dp, com folga de 0,8, de 0,4 e de 1,2 linha) fica em [arrastarAlcaFinalAte]; o
     * arraste é relativo (rb-48), como o de uma pessoa. Cada tentativa vai para as capturas, e a falha lista todas.
     */
    @Test
    fun c03_arrastarAlcaAmplia() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, regras, 0, PALAVRA)
        val antes = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção antes do arraste" }
        regras.fecharDialogosDoSistema()
        Capturas.anotar("c03", "antes=[${antes.inicio},${antes.fim}] texto='${antes.texto}'")
        Capturas.capturar("06_soler_alca_antes")

        val resultado = arrastarAlcaFinalAte(
            scenario = scenario,
            indiceParagrafo = 0,
            palavraAlvo = PALAVRA_ADIANTE,
            antes = antes,
            regras = regras,
            reselecionar = { selecionarPalavra(scenario, regras, 0, PALAVRA) },
            anotar = { Capturas.anotar("c03", it) },
            diagnostico = diagnosticoDoC03(scenario),
        )
        val depois = resultado.depois
        if (depois == null) {
            val mensagem = "Arrastar a alça não ampliou a seleção: fim antes=${antes.fim} " +
                "texto='${antes.texto}', alvo '$PALAVRA_ADIANTE', última seleção=${selecaoAtual(scenario, 0)}, " +
                "tentativas: " + resultado.tentativas.joinToString("; ")
            Capturas.capturar("07_soler_alca_depois")
            throw AssertionError(mensagem)
        }
        Capturas.anotar(
            "c03",
            "deu certo em [${resultado.sucesso}] depois=[${depois.inicio},${depois.fim}] texto='${depois.texto}'"
        )
        Capturas.capturar("07_soler_alca_depois")
    }

    /** Diagnóstico (rb-46diag) do C03: a primeira tentativa anota as posições, os passos e captura a tela. */
    private fun diagnosticoDoC03(scenario: ActivityScenario<ReaderActivity>): DiagnosticoDoArraste =
        object : DiagnosticoDoArraste {
            override fun antesDoArraste(alca: PointF, fim: PointF) =
                anotarDiagnosticoAntesDoArraste(scenario, alca, fim)

            override fun aoMover(inicioDoArraste: Long, passo: Int, x: Float, y: Float) =
                observarPassoDoArraste(scenario, inicioDoArraste, passo, x, y)

            override fun antesDeSoltar(inicioDoArraste: Long) = observarAntesDeSoltar(scenario, inicioDoArraste)
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
        selecionarPalavra(scenario, regras, 0, PALAVRA)
        val antes = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção antes do arraste" }
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
        val depois = esperarInicioMenorQue(scenario, 0, antes.inicio)
        Capturas.anotar("c03b", "depois=${depois ?: selecaoAtual(scenario, 0)}")
        Capturas.capturar("09_soler_alca_inicial")
        assertTrue(
            "O início da seleção deveria ficar antes de ${antes.inicio} (entrar '$PALAVRA_ANTERIOR'), " +
                "veio ${depois ?: selecaoAtual(scenario, 0)}",
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
        selecionarPalavra(scenario, regras, 0, PALAVRA)
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

        /** C16 (rb-58): um terço da área útil por rolagem, no máximo de rolagens, e a pausa para a lista parar. */
        const val FRACAO_DA_ROLAGEM_C16 = 0.33f
        const val MAXIMO_DE_ROLAGENS_C16 = 12
        const val PAUSA_DEPOIS_DA_ROLAGEM_C16_MS = 400L

        /** Palavra mais adiante no mesmo parágrafo, usada como alvo do arraste da alça. */
        const val PALAVRA_ADIANTE = "manhã"

        /** Palavra logo antes de 'chuva' (com o espaço, para achar só o 'A' do início do parágrafo 0). */
        const val PALAVRA_ANTERIOR = "A "

        /** Passos do arraste da primeira tentativa do C03 em que a seleção é anotada (diagnóstico rb-46diag). */
        val PASSOS_ANOTADOS = setOf(1, 5, 10, 15, 20)

        /** Passo do arraste em que a tela é capturada no meio do gesto (diagnóstico rb-46diag). */
        const val PASSO_DA_CAPTURA_DO_MEIO = 10

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
    }
}
