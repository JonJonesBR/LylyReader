package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
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
import com.jonjonesbr.audiobookgen.ui.apoio.rolarListaComODedo
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
    }
}
