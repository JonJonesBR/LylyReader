package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Botão Definir da barra da seleção no Só ler, no emulador (C13, rb-44): o toque abre a busca na web pelo significado
 * da palavra, sem seletor de apps. Sem espresso-intents no projeto, a intenção não é lida: o teste confere o que se vê
 * na tela. Se um app de busca ou navegador vai para a frente, o Leitor sai do primeiro plano e volta com Voltar. Se
 * nenhum trata a intenção, o app mostra o aviso e segue aberto (RESUMED). Nesse caminho o menu já fechou e desfez a
 * seleção antes do Definir, então o teste só anota o estado da seleção e da barra, sem asserção.
 */
@RunWith(AndroidJUnit4::class)
class DefinirNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    /**
     * C13 (rb-44): só ler, com a palavra selecionada, o botão Definir da barra leva a busca pelo significado (ou avisa
     * sem navegador). Em nenhum dos dois casos o app pode fechar ou travar.
     */
    @Test
    fun c13_definirAbreBuscaOuAvisaSemFechar() {
        val scenario = abrirSoLer()
        selecionarPalavra(scenario, regras, 0, PALAVRA, cenario = "c13")
        val selecao = checkNotNull(selecaoAtual(scenario, 0)) { "Sem seleção antes de tocar em Definir" }
        regras.fecharDialogosDoSistema()
        val (x, y) = Gestos.centroDaView(scenario, R.id.btnSelecaoTextoDefinir, "botão Definir da barra")
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        Capturas.anotar(
            "c13",
            "selecao=[${selecao.inicio},${selecao.fim}] texto='${selecao.texto}' " +
                "definir=(${x},${y}) consulta='${contexto.getString(R.string.definir_consulta, PALAVRA)}'",
        )
        Capturas.capturar("63_c13_antes_de_definir")

        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(x.toFloat(), y.toFloat())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pacote = contexto.packageName
        val outroAppNaFrente = esperarOutroAppNaFrente(device, pacote)
        Capturas.anotar(
            "c13",
            "app em primeiro plano depois do toque: ${device.currentPackageName} (outro app=$outroAppNaFrente)",
        )

        if (outroAppNaFrente) {
            Thread.sleep(PAUSA_MS)
            Capturas.capturar("64_c13_busca_em_primeiro_plano")
            assertFalse(
                "Com a busca em primeiro plano, o Leitor deveria estar fora do estado RESUMED",
                scenario.state == Lifecycle.State.RESUMED,
            )
            voltarAoLeitor(device, pacote, scenario)
            scenario.onActivity { a -> assertFalse("O Leitor não deveria ter fechado", a.isFinishing) }
        } else {
            // Nenhum app tratou a busca: o app mostra o aviso e segue aberto, com a seleção.
            val aviso = contexto.getString(R.string.toast_definir_indisponivel)
            Capturas.anotar("c13", "aviso visível no UiAutomator: ${device.hasObject(By.text(aviso))}")
            Capturas.capturar("64_c13_sem_navegador")
            assertEquals(
                "Sem navegador, o Leitor deveria seguir aberto (RESUMED)",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
            scenario.onActivity { a -> assertFalse("O Leitor não deveria ter fechado", a.isFinishing) }
            // Só anotado: o menu fecha e desfaz a seleção antes de chamar o Definir (MenuDaSelecaoDeTexto), então a
            // seleção e a barra podem já ter saído. Não é asserção deste teste.
            Capturas.anotar(
                "c13",
                "depois do aviso: selecaoAtual=${selecaoAtual(scenario, 0)} barra=${visibilidadeDaBarra(scenario)}",
            )
        }
    }

    /** Abre o livro de teste no Só ler e espera a lista de parágrafos (mesmo caminho dos testes do Só ler). */
    private fun abrirSoLer(): ActivityScenario<ReaderActivity> {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = LivroNoAparelho.gravarLivroNoCache()
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
     * Espera até [TEMPO_PARA_TROCA_DE_APP_MS] um app que não seja o do Leitor ficar em primeiro plano. Devolve se
     * algum apareceu. Roda na thread do teste (UiAutomator não roda na principal).
     */
    private fun esperarOutroAppNaFrente(device: UiDevice, pacote: String): Boolean {
        val fim = System.currentTimeMillis() + TEMPO_PARA_TROCA_DE_APP_MS
        while (System.currentTimeMillis() < fim) {
            if (device.currentPackageName != pacote) return true
            Thread.sleep(PAUSA_MS)
        }
        return false
    }

    /**
     * Volta ao Leitor com Voltar (até [MAXIMO_DE_VOLTAS] vezes, só enquanto outro app estiver na frente) e espera o
     * Leitor voltar a RESUMED. Falha se ele não voltar.
     */
    private fun voltarAoLeitor(device: UiDevice, pacote: String, scenario: ActivityScenario<ReaderActivity>) {
        var voltas = 0
        while (device.currentPackageName != pacote && voltas < MAXIMO_DE_VOLTAS) {
            device.pressBack()
            voltas++
            Thread.sleep(PAUSA_MS)
        }
        val fim = System.currentTimeMillis() + TEMPO_DE_VOLTA_MS
        while (scenario.state != Lifecycle.State.RESUMED && System.currentTimeMillis() < fim) {
            Thread.sleep(PAUSA_MS)
        }
        Capturas.anotar("c13", "voltas=$voltas no app=${device.currentPackageName} estado=${scenario.state}")
        assertEquals("O Leitor deveria voltar a RESUMED depois de Voltar", Lifecycle.State.RESUMED, scenario.state)
    }

    /** Visibilidade da barra da seleção, lida na thread principal. */
    private fun visibilidadeDaBarra(scenario: ActivityScenario<ReaderActivity>): Int {
        var visibilidade = View.GONE
        scenario.onActivity { a -> visibilidade = a.findViewById<View>(R.id.barraSelecaoTexto).visibility }
        return visibilidade
    }

    private companion object {
        const val PALAVRA = "chuva"

        /** Tempo para um app de busca ou navegador ir para a frente depois do toque em Definir. */
        const val TEMPO_PARA_TROCA_DE_APP_MS = 4_000L

        /** Tempo para o Leitor voltar a RESUMED depois do Voltar, e o máximo de Voltar até ele voltar. */
        const val TEMPO_DE_VOLTA_MS = 10_000L
        const val MAXIMO_DE_VOLTAS = 3

        const val PAUSA_MS = 300L
    }
}
