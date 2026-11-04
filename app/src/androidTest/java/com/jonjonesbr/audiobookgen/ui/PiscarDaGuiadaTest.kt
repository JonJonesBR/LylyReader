package com.jonjonesbr.audiobookgen.ui

import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.garantirPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sonda do "piscar" da leitura guiada (rb-65), no emulador. O relato: com a guiada tocando, depois de um toque curto
 * num parágrafo (C21) ou de escolher outro capítulo no painel (C22), a lista alterna entre a posição do parágrafo
 * narrado (marcado) e a de outro parágrafo (sem marcação, sem o chip "Voltar à narração").
 *
 * Os testes REPRODUZEM e DETECTAM: falham se o piscar existir. Cada um instala um ouvinte de rolagem antes do gesto
 * (quem rola, com o stack), amostra a tela a cada 50 ms por 4 s depois dele e anota tudo em medidas.txt (prefixo
 * c21/c22). As asserções contam só o que acontece depois de [ASSENTAMENTO_MS] do gesto.
 *
 * Voz: a longa (30 s por trecho). A curta muda de parágrafo a cada 1,5 s, e o narrado sairia do índice antes dos 4 s
 * de observação. A muito longa (240 s) quase não move o progresso nessa janela, e o acompanhamento que se quer
 * observar roda a cada progresso.
 */
@RunWith(AndroidJUnit4::class)
class PiscarDaGuiadaTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    private val instrumentacao get() = InstrumentationRegistry.getInstrumentation()

    private val device: UiDevice get() = UiDevice.getInstance(instrumentacao)

    private val pacote: String get() = instrumentacao.targetContext.packageName

    /**
     * C21 (rb-65): com a guiada tocando no 0, a lista rolada com o dedo para longe, e um toque curto no parágrafo 3
     * (longo), a guiada tem de ir até ele sem a marcação piscar nem a lista ir e voltar.
     */
    @Test
    fun c21_toqueCurtoDepoisDeRolarComODedoNaoPisca() {
        val contexto = instrumentacao.targetContext
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto, textos = TEXTOS)
        esperarGuiadaTocando(scenario, 0)
        regras.fecharDialogosDoSistema()

        val eventos = CopyOnWriteArrayList<EventoDeRolagem>()
        instalarOuvinte(scenario, eventos)
        rolarListaComODedo(
            scenario,
            fracaoDaAreaUtil = FRACAO_DA_ROLAGEM_C21,
            paraBaixoNoTexto = false,
            cenario = "c21",
        )
        val ponto = garantirPalavraVisivel(scenario, PARAGRAFO_C21, PALAVRA_LONGA, "c21")
        regras.fecharDialogosDoSistema()
        Capturas.capturar("52_c21_inicio")

        val marco = SystemClock.uptimeMillis()
        Gestos.toqueCurto(ponto.x, ponto.y)
        esperarAte(scenario, "leitura guiada no parágrafo $PARAGRAFO_C21") { _ ->
            estadoDaGuiada()?.index == PARAGRAFO_C21
        }
        val amostras = observar(scenario, eventos, marco, "c21", "53_c21_meio", "54_c21_fim")
        conferirPiscar("c21", amostras, eventos, marco)
    }

    /**
     * C22 (rb-65): com a guiada tocando no 0, o painel de capítulos aberto pelo toque real na barra e o capítulo 2
     * escolhido. A guiada passa a ler dali (índice do início do capítulo 2), e a lista não pode piscar.
     */
    @Test
    fun c22_escolherCapituloNoPainelNaoPisca() {
        val contexto = instrumentacao.targetContext
        regras.usarVoz(VozFalsa.longa(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto, textos = TEXTOS)
        esperarGuiadaTocando(scenario, 0)
        regras.fecharDialogosDoSistema()

        val eventos = CopyOnWriteArrayList<EventoDeRolagem>()
        instalarOuvinte(scenario, eventos)
        abrirPainelPeloToque(scenario)
        val inicioDoCapitulo2 = LivroNoAparelho.livroDeTeste(TEXTOS).capitulos[1].indiceParagrafoInicio
        val linhas = linhasDoPainel()
        assertTrue(
            "O painel deveria mostrar 2 capítulos no livro de teste, mas vi ${linhas.size}",
            linhas.size >= 2
        )
        val alvo = linhas[1].visibleCenter
        Capturas.capturar("55_c22_painel_aberto")

        regras.fecharDialogosDoSistema()
        val marco = SystemClock.uptimeMillis()
        device.click(alvo.x, alvo.y)
        esperarAte(scenario, "leitura guiada no início do capítulo 2 (parágrafo $inicioDoCapitulo2)") { _ ->
            estadoDaGuiada()?.index == inicioDoCapitulo2
        }
        val amostras = observar(scenario, eventos, marco, "c22", "56_c22_meio", "57_c22_fim")
        conferirPiscar("c22", amostras, eventos, marco)
    }

    /** Estado atual da guiada (índice, tocando, carregando). Nulo se não há sessão. */
    private fun estadoDaGuiada() = GuidedPlaybackBridge.manager?.state?.value

    /** Espera a guiada tocar o parágrafo [indice]. */
    private fun esperarGuiadaTocando(scenario: ActivityScenario<ReaderActivity>, indice: Int) {
        esperarAte(scenario, "leitura guiada tocando o parágrafo $indice") { _ ->
            estadoDaGuiada()?.let { it.isPlaying && it.index == indice } == true
        }
    }

    /** Lista de parágrafos do Leitor (lida na thread principal, dentro de onActivity). */
    private fun lista(a: ReaderActivity): RecyclerView = a.findViewById(R.id.recyclerViewParagrafos)

    /** Instala o ouvinte de rolagem na lista, na thread principal, antes do gesto. */
    private fun instalarOuvinte(scenario: ActivityScenario<ReaderActivity>, eventos: MutableList<EventoDeRolagem>) {
        scenario.onActivity { a -> lista(a).addOnScrollListener(OuvinteDeRolagem(eventos)) }
    }

    /**
     * Toca a área do capítulo atual (a barra que abre o painel), com um toque real, e espera a lista do painel na
     * janela do BottomSheetDialog. Igual ao C19 de CapitulosNoAparelhoTest.
     */
    private fun abrirPainelPeloToque(scenario: ActivityScenario<ReaderActivity>) {
        esperarAte(scenario, "barra do capítulo atual na tela") { a ->
            a.findViewById<View>(R.id.barraCapitulo).visibility == View.VISIBLE
        }
        val (x, y) = Gestos.centroDaView(scenario, R.id.areaCapituloAtual, "área do capítulo atual (abre o painel)")
        regras.fecharDialogosDoSistema()
        device.click(x, y)
        val abriu = device.wait(Until.hasObject(By.res(pacote, ID_LISTA_DO_PAINEL)), TEMPO_PAINEL_MS) == true
        if (!abriu) {
            fail(
                "Toque na área do capítulo atual não abriu o painel de capítulos " +
                    "(lista $ID_LISTA_DO_PAINEL ausente)"
            )
        }
    }

    /** Título de cada linha do painel aberto, de cima para baixo (vazio se o painel não está na tela). */
    private fun linhasDoPainel(): List<UiObject2> {
        val lista = device.findObject(By.res(pacote, ID_LISTA_DO_PAINEL)) ?: return emptyList()
        return lista.findObjects(By.res(pacote, ID_TITULO_DA_LINHA)).sortedBy { it.visibleCenter.y }
    }

    /**
     * Amostra a tela a cada 50 ms por [JANELA_DE_OBSERVACAO_MS] (a partir de agora), na thread do teste. Tira a captura
     * do meio e a do fim. Anota as amostras e as rolagens de [eventos] em medidas.txt, com o tempo a partir de [marco].
     */
    private fun observar(
        scenario: ActivityScenario<ReaderActivity>,
        eventos: List<EventoDeRolagem>,
        marco: Long,
        prefixo: String,
        capturaDoMeio: String,
        capturaDoFim: String,
    ): List<Amostra> {
        val amostras = mutableListOf<Amostra>()
        var semAmostra = 0
        var fizMeio = false
        val fim = SystemClock.uptimeMillis() + JANELA_DE_OBSERVACAO_MS
        while (SystemClock.uptimeMillis() < fim) {
            if (!fizMeio && SystemClock.uptimeMillis() >= fim - JANELA_DE_OBSERVACAO_MS / 2) {
                fizMeio = true
                Capturas.capturar(capturaDoMeio)
            }
            val amostra = lerAmostra(scenario)
            if (amostra == null) semAmostra++ else amostras += amostra
            Thread.sleep(INTERVALO_DE_AMOSTRA_MS)
        }
        Capturas.capturar(capturaDoFim)
        amostras.forEach { Capturas.anotar(prefixo, "amostra ${it.linha(marco)}") }
        eventos.forEach { Capturas.anotar(prefixo, "rolagem ${it.linha(marco)}") }
        Capturas.anotar(prefixo, "amostras=${amostras.size} semAmostra=$semAmostra rolagens=${eventos.size}")
        return amostras
    }

    /**
     * Lê a tela (guiada, item narrado, lista, chip e foco) na thread principal. Nulo se a Activity não está
     * pronta.
     */
    private fun lerAmostra(scenario: ActivityScenario<ReaderActivity>): Amostra? {
        var amostra: Amostra? = null
        try {
            scenario.onActivity { a ->
                val lista = lista(a)
                val lm = lista.layoutManager as LinearLayoutManager
                val estado = GuidedPlaybackBridge.manager?.state?.value
                val indice = estado?.index ?: -1
                val item = if (indice >= 0) lista.findViewHolderForAdapterPosition(indice)?.itemView else null
                val foco = lista.findFocus()?.let { lista.findContainingItemView(it) }
                amostra = Amostra(
                    tMs = SystemClock.uptimeMillis(),
                    indiceNarrado = indice,
                    paragrafoSync = ViewModelProvider(a)[ReaderViewModel::class.java]
                        .uiState.value.paragrafoSyncAtual,
                    progressPct = estado?.progressPct ?: -1f,
                    anexado = item != null && item.isAttachedToWindow,
                    fracaoVisivel = item?.let { fracaoVisivel(it, lista) } ?: 0f,
                    marcado = item?.background is LayerDrawable,
                    primeiroVisivel = lm.findFirstVisibleItemPosition(),
                    deslocamento = lista.computeVerticalScrollOffset(),
                    scrollState = lista.scrollState,
                    chipVoltar = a.findViewById<View>(R.id.fabVoltarNarracao).visibility == View.VISIBLE,
                    posicaoDoFoco = foco?.let { lista.getChildAdapterPosition(it) } ?: -1,
                    focoComSelecao = (foco as? TextView)?.hasSelection() == true,
                )
            }
        } catch (erro: IllegalStateException) {
            return null
        }
        return amostra
    }

    /**
     * Parte do item que está na área da lista, de 0 a 1. A referência é a menor altura entre o item e a lista: um
     * parágrafo mais alto que a tela conta como visível (1) enquanto ocupa a tela, e só some quando sai dela.
     * Zero se o item ou a lista não têm altura, ou se o item não está na tela.
     */
    private fun fracaoVisivel(item: View, lista: RecyclerView): Float {
        val referencia = minOf(item.height, lista.height)
        if (referencia <= 0) return 0f
        val visivel = Rect()
        if (!item.getGlobalVisibleRect(visivel)) return 0f
        return (visivel.height().toFloat() / referencia).coerceAtMost(1f)
    }

    /**
     * Confere o piscar depois de [ASSENTAMENTO_MS] do gesto. Falha se o item narrado alternar entre visível e
     * invisível mais de uma vez, se a marcação alternar mais de uma vez, ou se o sinal do dy inverter mais de duas
     * vezes. A mensagem resume as transições e os stacks de rolagem mais frequentes.
     */
    private fun conferirPiscar(nome: String, amostras: List<Amostra>, eventos: List<EventoDeRolagem>, marco: Long) {
        val janela = amostras.filter { it.tMs - marco >= ASSENTAMENTO_MS }
        val trocasDeVisibilidade = mudancas(janela) { estadoDeVisibilidade(it) }
        val trocasDeMarcacao = mudancas(janela) { it.marcado }
        val rolagens = eventos.filter { it.tMs - marco >= ASSENTAMENTO_MS }
        val sinais = rolagens.map { Integer.signum(it.dy) }
        val inversoes = sinais.zipWithNext().count { (antes, depois) -> antes != depois }
        val stacks = resumirStacks(rolagens) { it.chaveDaPilha }
        val stacksDoApp = resumirStacks(rolagens) { it.chaveDoApp }
        val textoVisibilidade = trocasDeVisibilidade.descrever(marco) { if (it) "visível" else "invisível" }
        val textoMarcacao = trocasDeMarcacao.descrever(marco) { if (it) "marcado" else "sem marcação" }
        val resumo = "visibilidade: $textoVisibilidade; " +
            "marcação: $textoMarcacao; " +
            "sinal do dy invertido $inversoes vez(es) em ${rolagens.size} rolagens"
        Capturas.anotar(nome, "resumo: $resumo")
        Capturas.anotar(nome, "stacks mais frequentes: ${stacks.ifEmpty { "nenhum" }}")
        Capturas.anotar(nome, "quadros do app mais frequentes: ${stacksDoApp.ifEmpty { "nenhum" }}")

        val falhas = mutableListOf<String>()
        if (trocasDeVisibilidade.size > TRANSICOES_MAXIMAS) {
            falhas += "o item narrado alternou entre visível e invisível ${trocasDeVisibilidade.size} vezes"
        }
        if (trocasDeMarcacao.size > TRANSICOES_MAXIMAS) {
            falhas += "a marcação da leitura alternou ${trocasDeMarcacao.size} vezes"
        }
        if (inversoes > INVERSOES_MAXIMAS) {
            falhas += "a rolagem inverteu de sentido $inversoes vezes"
        }
        if (falhas.isNotEmpty()) {
            fail(
                "Piscar da leitura guiada ($nome): ${falhas.joinToString("; ")}. $resumo. " +
                    "Stacks de rolagem mais frequentes: ${stacks.ifEmpty { "nenhum" }}. " +
                    "Quadros do app mais frequentes: ${stacksDoApp.ifEmpty { "nenhum" }}"
            )
        }
    }

    /** Os [TRECHOS_DE_STACK] grupos mais frequentes de [rolagens], pela [chave], como "Nx chave | ...". */
    private fun resumirStacks(rolagens: List<EventoDeRolagem>, chave: (EventoDeRolagem) -> String): String =
        rolagens.groupingBy(chave).eachCount().entries
            .sortedByDescending { it.value }
            .take(TRECHOS_DE_STACK)
            .joinToString(" | ") { "${it.value}x ${it.key}" }

    /** Visível (true), invisível (false) ou nulo (no meio do caminho, sem conta). */
    private fun estadoDeVisibilidade(amostra: Amostra): Boolean? = when {
        amostra.fracaoVisivel > LIMITE_VISIVEL -> true
        amostra.fracaoVisivel < LIMITE_INVISIVEL -> false
        else -> null
    }

    /** Pontos em que o valor de [valor] muda de um valor conhecido para outro. Os nulos não entram na conta. */
    private fun <T : Any> mudancas(amostras: List<Amostra>, valor: (Amostra) -> T?): List<Pair<Amostra, T>> {
        val saida = mutableListOf<Pair<Amostra, T>>()
        var anterior: T? = null
        for (amostra in amostras) {
            val atual = valor(amostra) ?: continue
            if (anterior != null && atual != anterior) saida += amostra to atual
            anterior = atual
        }
        return saida
    }

    private companion object {
        /** Parágrafo longo (o 3, que o toque curto leva a guiada até), e a palavra que se toca nele. */
        const val PARAGRAFO_C21 = 3
        const val PALAVRA_LONGA = "Frase"

        /** Fração da área útil que o dedo anda para longe do parágrafo 0 antes do toque (C21). */
        const val FRACAO_DA_ROLAGEM_C21 = 0.9f

        /**
         * Parágrafos longos em 1, 3 e 5 (o 5 é o início do capítulo 2, bem abaixo da tela). Os demais são curtos.
         */
        val TEXTOS: List<String> = LivroNoAparelho.PARAGRAFOS.toMutableList().apply {
            for (indice in listOf(1, 3, 5)) this[indice] = LivroNoAparelho.PARAGRAFO_LONGO
        }

        /** Janela de observação depois do gesto, e o intervalo entre amostras. */
        const val JANELA_DE_OBSERVACAO_MS = 4_000L
        const val INTERVALO_DE_AMOSTRA_MS = 50L

        /** Tempo, depois do gesto, em que o acompanhamento ainda pode se acomodar (fora das asserções). */
        const val ASSENTAMENTO_MS = 500L

        /** Fração do item visível para contar como visível (acima) ou invisível (abaixo). */
        const val LIMITE_VISIVEL = 0.3f
        const val LIMITE_INVISIVEL = 0.05f

        /** Quantas trocas de visibilidade, de marcação e de sentido da rolagem o teste aceita. */
        const val TRANSICOES_MAXIMAS = 1
        const val INVERSOES_MAXIMAS = 2

        /** Quantos stacks, e quantas linhas de cada stack, vão para a mensagem de falha. */
        const val TRECHOS_DE_STACK = 3

        /** Ids do painel de capítulos (bottom_sheet_capitulos e item_capitulo_toc), usados pelo UiAutomator. */
        const val ID_LISTA_DO_PAINEL = "rvCapitulosToc"
        const val ID_TITULO_DA_LINHA = "tvCapituloTocTitulo"

        /** Tempo para o painel aparecer depois do toque. */
        const val TEMPO_PAINEL_MS = 5_000L
    }
}

/** Uma amostra da tela, lida na thread principal. [tMs] é o uptime do instante da leitura. */
private class Amostra(
    val tMs: Long,
    val indiceNarrado: Int,
    val paragrafoSync: Int,
    val progressPct: Float,
    val anexado: Boolean,
    val fracaoVisivel: Float,
    val marcado: Boolean,
    val primeiroVisivel: Int,
    val deslocamento: Int,
    val scrollState: Int,
    val chipVoltar: Boolean,
    val posicaoDoFoco: Int,
    val focoComSelecao: Boolean,
)

/**
 * Uma rolagem com dy diferente de zero: quando, quanto, em que estado e quem a causou. [pilha] são os primeiros
 * quadros da pilha (app e framework); [pilhaDoApp] só os quadros do pacote do app.
 */
private class EventoDeRolagem(
    val tMs: Long,
    val dy: Int,
    val scrollState: Int,
    val pilha: List<String>,
    val pilhaDoApp: List<String>,
) {
    /** Chave de agrupamento: poucos quadros, para que rolagens do mesmo caminho caiam no mesmo grupo. */
    val chaveDaPilha: String get() = pilha.take(QUADROS_DA_CHAVE).joinToString(" < ")

    val chaveDoApp: String get() = pilhaDoApp.take(QUADROS_DA_CHAVE)
        .joinToString(" < ")
        .ifEmpty { "nenhum quadro do app" }
}

/** Quadros de [EventoDeRolagem.chaveDaPilha] (a chave agrupa só os primeiros). */
private const val QUADROS_DA_CHAVE = 4

/**
 * Ouvinte de rolagem da lista do Leitor (rb-65). A cada rolagem com dy diferente de zero, guarda o instante, o dy, o
 * estado e as primeiras linhas da pilha que a causou. Os quadros do próprio ouvinte e do sistema ficam de fora.
 * Rolagens por animação (smoothScrollToPosition) passam pelo ViewFlinger e pelo Choreographer, então a pilha pode
 * não mostrar quem pediu a animação: o `scrollState` (SETTLING ou DRAGGING) diz o caminho.
 */
private class OuvinteDeRolagem(private val eventos: MutableList<EventoDeRolagem>) : RecyclerView.OnScrollListener() {

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (dy == 0) return
        val quadros = Throwable().stackTrace.filterNot { ignorarQuadro(it.className) }
        eventos += EventoDeRolagem(
            tMs = SystemClock.uptimeMillis(),
            dy = dy,
            scrollState = recyclerView.scrollState,
            pilha = quadros.take(LINHAS_DE_PILHA).map { formatarQuadro(it) },
            pilhaDoApp = quadros.filter { it.className.startsWith(PACOTE_DO_APP) }
                .take(LINHAS_DE_PILHA)
                .map { formatarQuadro(it) },
        )
    }

    private fun formatarQuadro(quadro: StackTraceElement): String =
        "${quadro.className.substringAfterLast('.')}.${quadro.methodName}:${quadro.lineNumber}"

    private fun ignorarQuadro(classe: String): Boolean =
        classe.startsWith(OuvinteDeRolagem::class.java.name) || PREFIXOS_IGNORADOS.any { classe.startsWith(it) }

    private companion object {
        const val LINHAS_DE_PILHA = 8
        const val PACOTE_DO_APP = "com.jonjonesbr."

        /** Quadros do sistema e do teste que não dizem quem rolou (o app e o RecyclerView ficam). */
        val PREFIXOS_IGNORADOS = listOf(
            "java.", "kotlin.", "android.os.", "org.junit.", "androidx.test.", "dalvik.", "sun.", "jdk.",
        )
    }
}

/** Linha de medidas.txt de uma amostra, com o tempo a partir do gesto ([marco]). */
private fun Amostra.linha(marco: Long): String =
    "+${tMs - marco} ms guiada=$indiceNarrado sync=$paragrafoSync progresso=${"%.3f".format(progressPct)} " +
        "anexado=$anexado fracao=${"%.2f".format(fracaoVisivel)} marcado=$marcado primeiroVisivel=$primeiroVisivel " +
        "deslocamento=$deslocamento scroll=$scrollState chipVoltar=$chipVoltar foco=$posicaoDoFoco " +
        "selecaoNoFoco=$focoComSelecao"

/** Linha de medidas.txt de uma rolagem, com o tempo a partir do gesto ([marco]). */
private fun EventoDeRolagem.linha(marco: Long): String =
    "+${tMs - marco} ms dy=$dy scroll=$scrollState pilha=$chaveDaPilha app=$chaveDoApp"

/** Texto das trocas (instante e novo valor) para a mensagem de falha. */
private fun <T> List<Pair<Amostra, T>>.descrever(marco: Long, rotulo: (T) -> String): String =
    if (isEmpty()) "nenhuma" else joinToString(", ") { "+${it.first.tMs - marco} ms ${rotulo(it.second)}" }
