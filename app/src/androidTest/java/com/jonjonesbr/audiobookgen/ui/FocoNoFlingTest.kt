package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
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
import com.jonjonesbr.audiobookgen.ui.apoio.garantirPalavraVisivel
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sonda do foco no fling (rb-59, C23), no Só ler do emulador. O achado (Opus, confiança baixa): quando o parágrafo com
 * foco é reciclado no meio de um fling para baixo, o foco pula para outro parágrafo (rootViewRequestFocus), e
 * RecyclerView.requestChildFocus pode rolar a lista por fora da regra do rb-55 (requestChildRectangleOnScreen),
 * puxando a lista para trás ou parando o fling.
 *
 * A sonda dá foco ao parágrafo 0 com um toque curto, faz um fling longo para baixo e registra cada rolagem (dy, estado e
 * quem rolou), cada troca de estado e a posição do foco antes e depois. A asserção é a do achado: durante o fling para
 * baixo não há dy negativo. As medidas vão para medidas.txt com o prefixo c23.
 */
@RunWith(AndroidJUnit4::class)
class FocoNoFlingTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    /**
     * C23 (rb-59, sonda): só ler, foco no parágrafo 0, fling longo para baixo. Durante o fling a lista não pode andar
     * para trás (nenhum dy negativo). Registra tudo para o artefato, mesmo sem a asserção passar.
     */
    @Test
    fun c23_focoNaoPuxaAListaParaTrasNoFlingParaBaixo() {
        val scenario = abrirSoLerLongo()
        regras.fecharDialogosDoSistema()

        // Foco: um toque curto na palavra do parágrafo 0. Se o toque não der foco ao TextView, pede foco por código.
        garantirPalavraVisivel(scenario, 0, PALAVRA, "c23")
        regras.fecharDialogosDoSistema()
        val ponto = Gestos.pontoDaPalavra(scenario, 0, PALAVRA)
        Gestos.toqueCurto(ponto.x, ponto.y)
        if (!tvDoParagrafo0Focado(scenario)) {
            scenario.onActivity { a -> Gestos.textoDoParagrafo(a, 0).requestFocus() }
            Capturas.anotar("c23", "foco pedido por código: o toque curto não deu foco ao parágrafo 0")
        }
        val focoAntes = posicaoDoFoco(scenario)
        Capturas.anotar("c23", "foco antes do fling: posição=$focoAntes")
        Capturas.capturar("65_c23_antes_do_fling")

        val rolagens = CopyOnWriteArrayList<RegistroDoFling>()
        val estados = CopyOnWriteArrayList<TrocaDeEstadoDoFling>()
        scenario.onActivity { a -> listaDoLeitor(a).addOnScrollListener(OuvinteDoFling(rolagens, estados)) }

        // Fling longo para baixo: o dedo sobe quase a área inteira, em 10 passos de 5 ms (velocidade de fling).
        regras.fecharDialogosDoSistema()
        val retangulo = retanguloDaLista(scenario)
        val x = retangulo.centerX().toFloat()
        val yInicio = retangulo.top + retangulo.height() * 0.85f
        val yFim = retangulo.top + retangulo.height() * 0.15f
        val inicio = SystemClock.uptimeMillis()
        Gestos.arrastar(x, yInicio, x, yFim, passos = 10, msPorPasso = 5L)
        Thread.sleep(ESPERA_APOS_SOLTAR_MS)
        esperarAte(scenario, "lista parada depois do fling", timeoutMs = TEMPO_PARA_LISTA_PARAR_MS) { a ->
            listaDoLeitor(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        val fim = SystemClock.uptimeMillis()
        regras.fecharDialogosDoSistema()

        val durante = rolagens.filter { it.tMs >= inicio && it.tMs <= fim }
        val negativos = durante.filter { it.dy < 0 }
        val somaDy = durante.sumOf { it.dy }
        val focoDepois = posicaoDoFoco(scenario)
        Capturas.anotar(
            "c23",
            "fling: eventos=${durante.size} soma dy=$somaDy negativos=${negativos.size} " +
                "foco antes=$focoAntes depois=$focoDepois duracao=${fim - inicio} ms",
        )
        Capturas.anotar("c23", "stacks dos dy positivos: ${resumirStacks(durante.filter { it.dy > 0 })}")
        Capturas.anotar("c23", "stacks dos dy negativos: ${resumirStacks(negativos)}")
        val saidas = estados.filter { it.tMs >= inicio && it.tMs <= fim }
            .map { "+${it.tMs - inicio} ms estado=${it.estado}" }
        Capturas.anotar("c23", "trocas de estado no fling: ${saidas.ifEmpty { listOf("nenhuma") }}")
        durante.forEach {
            Capturas.anotar("c23", "rolagem +${it.tMs - inicio} ms dy=${it.dy} estado=${it.estado} pilha=${it.pilha}")
        }
        Capturas.capturar("66_c23_depois_do_fling")

        assertTrue(
            "A sonda não viu nenhuma rolagem no fling (eventos=${durante.size}); o gesto não virou fling",
            durante.isNotEmpty(),
        )
        assertTrue(
            "No fling para baixo a lista andou para trás em ${negativos.size} rolagens (soma dy=$somaDy). " +
                "Stacks: ${resumirStacks(negativos)}",
            negativos.isEmpty(),
        )
    }

    /** Abre o livro de teste no Só ler, com os textos de sempre repetidos três vezes (30 parágrafos, vários telas). */
    private fun abrirSoLerLongo(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val textos = LivroNoAparelho.PARAGRAFOS + LivroNoAparelho.PARAGRAFOS + LivroNoAparelho.PARAGRAFOS
        val arquivo = LivroNoAparelho.gravarLivroNoCache(textos)
        val intent = Intent(contexto, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)
        val scenario = regras.abrir(intent)
        esperarAte(scenario, "parágrafos na tela") { a ->
            listaDoLeitor(a).adapter?.itemCount == textos.size &&
                listaDoLeitor(a).findViewHolderForAdapterPosition(0) != null
        }
        return scenario
    }

    /** A lista de parágrafos do Leitor. Na thread principal. */
    private fun listaDoLeitor(a: ReaderActivity): RecyclerView = a.findViewById(R.id.recyclerViewParagrafos)

    /** Se o TextView do parágrafo 0 tem o foco agora, lido na thread principal. */
    private fun tvDoParagrafo0Focado(scenario: ActivityScenario<ReaderActivity>): Boolean {
        var focado = false
        scenario.onActivity { a -> focado = Gestos.textoDoParagrafo(a, 0).isFocused }
        return focado
    }

    /** Posição do parágrafo que tem o foco (-1 se nenhum), lida na thread principal. */
    private fun posicaoDoFoco(scenario: ActivityScenario<ReaderActivity>): Int {
        var posicao = -1
        scenario.onActivity { a ->
            val lista = listaDoLeitor(a)
            val foco = lista.findFocus()
            val item = foco?.let { lista.findContainingItemView(it) }
            posicao = item?.let { lista.getChildAdapterPosition(it) } ?: -1
        }
        return posicao
    }

    /** Retângulo visível da lista na tela, lido na thread principal. */
    private fun retanguloDaLista(scenario: ActivityScenario<ReaderActivity>): Rect {
        val retangulo = Rect()
        scenario.onActivity { a -> listaDoLeitor(a).getGlobalVisibleRect(retangulo) }
        return retangulo
    }

    /** Os [QUADROS_DO_RESUMO] grupos mais frequentes de [rolagens], pela pilha, como "Nx pilha | ...". */
    private fun resumirStacks(rolagens: List<RegistroDoFling>): String =
        rolagens.groupingBy { it.pilha }.eachCount().entries
            .sortedByDescending { it.value }
            .take(QUADROS_DO_RESUMO)
            .joinToString(" | ") { "${it.value}x ${it.key}" }
            .ifEmpty { "nenhuma" }

    private companion object {
        const val PALAVRA = "chuva"

        /** Espera depois de soltar o dedo, antes de esperar a lista parar (o fling começa a andar). */
        const val ESPERA_APOS_SOLTAR_MS = 200L

        /** Até quanto esperar a lista parar depois do fling. */
        const val TEMPO_PARA_LISTA_PARAR_MS = 15_000L

        /** Quantos grupos de pilha entram no resumo das medidas. */
        const val QUADROS_DO_RESUMO = 3
    }
}

/**
 * Uma rolagem da lista do Leitor com dy diferente de zero (rb-59): o instante, o dy, o estado da lista e os primeiros
 * quadros relevantes (app, RecyclerView e View) que a causaram ([pilha]).
 */
private class RegistroDoFling(val tMs: Long, val dy: Int, val estado: Int, val pilha: String)

/** Uma troca de estado da lista (rb-59): o instante e o novo estado (IDLE, DRAGGING ou SETTLING). */
private class TrocaDeEstadoDoFling(val tMs: Long, val estado: Int)

/**
 * Ouvinte da lista do Leitor para o fling (rb-59). Guarda cada rolagem com dy diferente de zero, com os quadros do
 * app que a causaram (sem o próprio ouvinte), e cada troca de estado, com o instante.
 */
private class OuvinteDoFling(
    private val rolagens: MutableList<RegistroDoFling>,
    private val estados: MutableList<TrocaDeEstadoDoFling>,
) : RecyclerView.OnScrollListener() {

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (dy == 0) return
        rolagens += RegistroDoFling(
            tMs = SystemClock.uptimeMillis(),
            dy = dy,
            estado = recyclerView.scrollState,
            pilha = quadrosDoApp(),
        )
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        estados += TrocaDeEstadoDoFling(SystemClock.uptimeMillis(), newState)
    }

    /**
     * Os quadros relevantes da pilha atual, no formato Classe.metodo:linha: do app, e também do RecyclerView e da View
     * do Android (é lá que está o requestChildFocus e o fling). Sem o próprio ouvinte, sem Throwable e sem dalvik.
     */
    private fun quadrosDoApp(): String = Throwable().stackTrace
        .filter { quadroRelevante(it) }
        .take(QUADROS_DA_PILHA)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        .ifEmpty { "nenhum quadro relevante" }

    private fun quadroRelevante(quadro: StackTraceElement): Boolean {
        val classe = quadro.className
        if (classe.contains(NOME_DO_OUVINTE)) return false
        if (classe.startsWith("java.lang.Throwable") || classe.startsWith("dalvik.")) return false
        return classe.startsWith(PACOTE_DO_APP) ||
            classe.startsWith(PACOTE_DO_RECYCLER) ||
            classe.startsWith(PACOTE_DE_VIEW)
    }

    private companion object {
        const val PACOTE_DO_APP = "com.jonjonesbr."
        const val PACOTE_DO_RECYCLER = "androidx.recyclerview."
        const val PACOTE_DE_VIEW = "android.view."
        const val NOME_DO_OUVINTE = "OuvinteDoFling"
        const val QUADROS_DA_PILHA = 12
    }
}
