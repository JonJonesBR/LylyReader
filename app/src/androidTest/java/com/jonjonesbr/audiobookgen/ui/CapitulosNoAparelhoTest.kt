package com.jonjonesbr.audiobookgen.ui

import android.view.Surface
import android.view.View
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
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.TEMPO_LIMITE_ESPERA_MS
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Painel de capítulos girando a tela no emulador (rb-46, C19 do rb-18). Usa o livro de dois capítulos e a guiada
 * pausada: o "Ler sem áudio" não mostra a marcação de capítulos para converter (a caixa some), e o cenário precisa
 * dela. O painel abre com um toque real na área do capítulo atual, a caixa do capítulo 2 é tocada, e a tela gira de
 * verdade (UiDevice). Depois do giro, a Activity nova tem de reabrir o painel no mesmo estado (rb-18). Os toques são
 * do UiDevice (outra janela, a do BottomSheetDialog); as leituras de View da Activity passam pela thread principal.
 */
@RunWith(AndroidJUnit4::class)
class CapitulosNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    private val instrumentacao get() = InstrumentationRegistry.getInstrumentation()

    private val device: UiDevice get() = UiDevice.getInstance(instrumentacao)

    private val pacote: String get() = instrumentacao.targetContext.packageName

    /**
     * C19 (rb-18): com o painel aberto e o capítulo 2 marcado, girar a tela para a paisagem e voltar. Em cada giro, a
     * Activity nova reabre o painel, com o capítulo 2 ainda marcado.
     */
    @Test
    fun c19_girarComPainelAbertoReabreOPainelComAMarcacao() {
        val scenario = abrirLeitorPausado()
        abrirPainel(scenario, "40_c19_painel_aberto")
        marcarSegundoCapitulo()
        Capturas.capturar("41_c19_capitulo_marcado")

        comRotacaoManual {
            girarEConferir(scenario, paisagem = true, captura = "42_c19_paisagem_painel_reabriu")
            girarEConferir(scenario, paisagem = false, captura = "43_c19_retrato_painel_reabriu")
        }
    }

    /**
     * C19b (rb-18): com o painel fechado (Voltar), girar a tela não o reabre. Confere que a Activity foi recriada e
     * que a barra do capítulo voltou à tela, para o "não reabriu" não passar por falta de tempo.
     */
    @Test
    fun c19b_fecharOPainelEGirarNaoOReabre() {
        val scenario = abrirLeitorPausado()
        abrirPainel(scenario, "40b_c19b_painel_aberto")

        regras.fecharDialogosDoSistema()
        device.pressBack()
        val fechou = device.wait(Until.gone(By.res(pacote, ID_LISTA)), TEMPO_PAINEL_MS) == true
        Capturas.anotar("c19b-fechado", "painelFechado=$fechou")
        if (!fechou) {
            fail("Voltar com o painel de capítulos aberto não o fechou: a lista ainda aparece na tela")
        }
        Capturas.capturar("44_c19b_painel_fechado")

        comRotacaoManual {
            val antes = instanciaAtual(scenario)
            device.setOrientationLeft()
            esperarLeitorRecriado(scenario, antes, "paisagem")
            val reabriu = device.wait(Until.hasObject(By.res(pacote, ID_LISTA)), TEMPO_SEM_PAINEL_MS) == true
            Capturas.anotar(
                "c19b-girado",
                "rotacao=${device.displayRotation} painelReaberto=$reabriu"
            )
            Capturas.capturar("45_c19b_paisagem_sem_painel")
            assertTrue(
                "Painel fechado com Voltar e a tela girada para a paisagem: o painel não deveria reabrir, mas abriu",
                !reabriu
            )
        }
    }

    /**
     * Gira para a paisagem ou para o retrato, espera a Activity nova com a barra do capítulo pronta e confere o
     * painel reaberto com a marcação do capítulo 2.
     */
    private fun girarEConferir(scenario: ActivityScenario<ReaderActivity>, paisagem: Boolean, captura: String) {
        val antes = instanciaAtual(scenario)
        val momento = if (paisagem) "paisagem" else "retrato"
        if (paisagem) device.setOrientationLeft() else device.setOrientationNatural()
        esperarLeitorRecriado(scenario, antes, momento)

        val rotacao = device.displayRotation
        val girou = if (paisagem) rotacao != Surface.ROTATION_0 else rotacao == Surface.ROTATION_0
        assertTrue(
            "Pedi a tela na $momento, mas a rotação do aparelho ficou em $rotacao",
            girou
        )

        val painel = device.wait(Until.hasObject(By.res(pacote, ID_LISTA)), TEMPO_PAINEL_MS) == true
        val checados = esperarChecados(TEMPO_PAINEL_MS) { it.getOrNull(1) == true }
        Capturas.anotar("c19-$momento", "rotacao=$rotacao painelReaberto=$painel checados=$checados")
        Capturas.capturar(captura)
        assertTrue(
            "Girei a tela para a $momento com o painel aberto, e o painel não reabriu (rb-18). " +
                "Viu: painelReaberto=$painel",
            painel
        )
        assertEquals(
            "Girei a tela para a $momento: o painel reabriu, mas o capítulo 2 não estava marcado. " +
                "Viu as caixas (de cima para baixo): $checados",
            true,
            checados.getOrNull(1)
        )
    }

    /**
     * Abre a guiada tocando com a voz muito longa e a pausa com o botão real. A guiada fica parada no parágrafo 0 (o
     * capítulo 1), sem avançar enquanto o teste age.
     */
    private fun abrirLeitorPausado(): ActivityScenario<ReaderActivity> {
        val contexto = instrumentacao.targetContext
        regras.usarVoz(VozFalsa.muitoLonga(contexto))
        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
        }
        pausarPeloBotao(scenario)
        return scenario
    }

    /** Pausa a guiada com um toque real no botão de tocar/pausar e espera a pausa. */
    private fun pausarPeloBotao(scenario: ActivityScenario<ReaderActivity>) {
        val (x, y) = Gestos.centroDaView(scenario, R.id.btnGuidedPlayPause, "botão de tocar/pausar da leitura guiada")
        regras.fecharDialogosDoSistema()
        device.click(x, y)
        esperarAte(scenario, "leitura guiada pausada") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.isPlaying == false
        }
        regras.fecharDialogosDoSistema()
    }

    /**
     * Toca a área do capítulo atual (a barra "1/2 · Título", que abre o painel) e espera a lista do painel na janela
     * do BottomSheetDialog. Falha dizendo o que se viu se o painel não abrir.
     */
    private fun abrirPainel(scenario: ActivityScenario<ReaderActivity>, captura: String) {
        esperarAte(scenario, "barra do capítulo atual na tela") { a ->
            a.findViewById<View>(R.id.barraCapitulo).visibility == View.VISIBLE
        }
        val (x, y) = Gestos.centroDaView(scenario, R.id.areaCapituloAtual, "área do capítulo atual (abre o painel)")
        regras.fecharDialogosDoSistema()
        device.click(x, y)
        val abriu = device.wait(Until.hasObject(By.res(pacote, ID_LISTA)), TEMPO_PAINEL_MS) == true
        Capturas.anotar("c19-painel", "abriuPeloToque=$abriu linhas=${checkboxesDaLista().size}")
        if (!abriu) {
            fail("Toque na área do capítulo atual não abriu o painel de capítulos (lista rvCapitulosToc ausente)")
        }
        Capturas.capturar(captura)
    }

    /**
     * Toca a caixa de "converter" do capítulo 2 (a segunda linha do painel) e espera a marcação aparecer. Antes do
     * toque, confere que a caixa estava desmarcada, para o teste não passar por estado que já vinha.
     */
    private fun marcarSegundoCapitulo() {
        val caixas = checkboxesDaLista()
        assertTrue(
            "O painel deveria ter uma caixa por capítulo (2 no livro de teste), mas vi ${caixas.size}",
            caixas.size >= 2
        )
        val alvo = caixas[1]
        assertTrue(
            "A caixa do capítulo 2 já vinha marcada antes do toque; o teste não tem o que conferir",
            !alvo.isChecked
        )
        device.click(alvo.visibleCenter.x, alvo.visibleCenter.y)
        val marcou = esperarChecados(TEMPO_PAINEL_MS) { it.getOrNull(1) == true }
        Capturas.anotar("c19-marcacao", "checados=$marcou")
        assertEquals(
            "Toquei a caixa do capítulo 2 e ela não ficou marcada. Viu as caixas (de cima para baixo): $marcou",
            true,
            marcou.getOrNull(1)
        )
    }

    /** Caixas "converter" das linhas do painel aberto, de cima para baixo (vazio se o painel não está na tela). */
    private fun checkboxesDaLista(): List<UiObject2> {
        val lista = device.findObject(By.res(pacote, ID_LISTA)) ?: return emptyList()
        return lista.findObjects(By.res(pacote, ID_CAIXA)).sortedBy { it.visibleCenter.y }
    }

    /**
     * Estado das caixas do painel (de cima para baixo) até [condicao] ser verdadeira ou estourar [timeoutMs].
     * Devolve o último estado lido, para a mensagem de falha.
     */
    private fun esperarChecados(timeoutMs: Long, condicao: (List<Boolean>) -> Boolean): List<Boolean> {
        val fim = System.currentTimeMillis() + timeoutMs
        var ultimo: List<Boolean> = emptyList()
        while (System.currentTimeMillis() < fim) {
            ultimo = checkboxesDaLista().map { it.isChecked }
            if (condicao(ultimo)) return ultimo
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        return ultimo
    }

    /**
     * Espera a Activity nova (outra instância que [antes]) com a barra do capítulo visível e a lista de parágrafos
     * já com linhas. Só então o painel pode ser conferido. Durante a recriação, a leitura pode não ter Activity
     * (ver [comActividade]): nesse caso tenta de novo até o tempo estourar, e a falha diz o último estado visto.
     */
    private fun esperarLeitorRecriado(scenario: ActivityScenario<ReaderActivity>, antes: Int, momento: String) {
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        var visto = "nenhuma Activity disponível"
        while (System.currentTimeMillis() < fim) {
            val pronto = comActividade(scenario) { a ->
                val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
                val nova = System.identityHashCode(a) != antes
                val barraVisivel = a.findViewById<View>(R.id.barraCapitulo).visibility == View.VISIBLE
                val itens = lista.adapter?.itemCount ?: 0
                visto = "nova=$nova barraVisivel=$barraVisivel linhasDaLista=$itens"
                nova && barraVisivel && itens > 0
            }
            if (pronto == true) return
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        throw AssertionError(
            "Leitor não voltou depois de girar para a $momento em ${TEMPO_LIMITE_ESPERA_MS / 1000} s. Viu: $visto"
        )
    }

    /** Identidade da Activity atual; tenta de novo enquanto a recriação não entrega uma instância. */
    private fun instanciaAtual(scenario: ActivityScenario<ReaderActivity>): Int {
        val fim = System.currentTimeMillis() + TEMPO_LIMITE_ESPERA_MS
        while (System.currentTimeMillis() < fim) {
            comActividade(scenario) { System.identityHashCode(it) }?.let { return it }
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
        }
        throw AssertionError("Não consegui ler a Activity do Leitor em ${TEMPO_LIMITE_ESPERA_MS / 1000} s")
    }

    /**
     * Roda [leitura] na Activity atual, na thread do teste. Logo depois de um giro, o ActivityScenario pode estar com
     * a instância antiga destruída, ou com a nova ainda sem resumir, e então onActivity lança IllegalStateException:
     * isso é só "ainda não", e a função devolve nulo para quem chama tentar de novo.
     */
    private fun <T> comActividade(scenario: ActivityScenario<ReaderActivity>, leitura: (ReaderActivity) -> T): T? {
        var resultado: T? = null
        try {
            scenario.onActivity { a -> resultado = leitura(a) }
        } catch (erro: IllegalStateException) {
            return null
        }
        return resultado
    }

    /**
     * Desliga a rotação automática antes de [corpo] e, no fim (mesmo se falhar), volta à orientação natural,
     * solta a rotação e restaura o ajuste de rotação automática que estava antes.
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

    private companion object {
        /** Id da lista do painel (bottom_sheet_capitulos), usado pelo UiAutomator com o pacote do app. */
        const val ID_LISTA = "rvCapitulosToc"

        /** Id da caixa "converter" de cada linha do painel (item_capitulo_toc). */
        const val ID_CAIXA = "chkCapituloTocConverter"

        /** Tempo para o painel aparecer depois de um toque ou de um giro. */
        const val TEMPO_PAINEL_MS = 5_000L

        /** Tempo de observação para afirmar que o painel NÃO reabriu depois de um giro. */
        const val TEMPO_SEM_PAINEL_MS = 3_000L

        const val INTERVALO_DE_LEITURA_MS = 100L
    }
}
