package com.jonjonesbr.audiobookgen.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_TELA_MS = 30_000L
private const val INTERVALO_ESPERA_MS = 50L
private const val POS_ARRAY_TAMANHO = 2

/**
 * Confere o estado vazio da tela de Audiobooks ([LibraryActivity]) em paisagem no emulador:
 * textos e botões ficam visíveis, sem corte horizontal e acessíveis na rolagem acima da barra de
 * navegação.
 */
@RunWith(AndroidJUnit4::class)
class AudiobooksVazioPaisagemNoAparelhoTest {

    @Test
    fun estadoVazioEmPaisagemFicaVisivelESemSobreposicao() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val contexto = instrumentacao.targetContext
        val permissao = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        instrumentacao.uiAutomation.grantRuntimePermission(contexto.packageName, permissao)
        AppPrefs(contexto).onboardingDone = true

        val device = UiDevice.getInstance(instrumentacao)
        val scenario = ActivityScenario.launch<LibraryActivity>(
            Intent(contexto, LibraryActivity::class.java)
        )
        try {
            esperarNaTela(scenario, "estado vazio visível no retrato") { a ->
                a.findViewById<View>(R.id.layoutVazio).visibility == View.VISIBLE &&
                    a.findViewById<View>(R.id.btnEscolherLivroVazio).visibility == View.VISIBLE
            }

            device.setOrientationLeft()

            esperarNaTela(scenario, "estado vazio em paisagem após giro") { a ->
                a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    a.findViewById<View>(R.id.layoutVazio).visibility == View.VISIBLE &&
                    a.findViewById<View>(R.id.navPrincipal).height > 0
            }

            scenario.onActivity { a ->
                val layoutVazio = a.findViewById<ViewGroup>(R.id.layoutVazio)
                val scroll = encontrarScrollView(layoutVazio)
                    ?: run {
                        fail("ScrollView não encontrado dentro de layoutVazio")
                        return@onActivity
                    }
                val nav = a.findViewById<View>(R.id.navPrincipal)
                val tvMsg = a.findViewById<View>(R.id.tvMsgVazio)
                val tvSub = a.findViewById<View>(R.id.tvSubMsgVazio)
                val btnEscolher = a.findViewById<View>(R.id.btnEscolherLivroVazio)
                val btnImportar = a.findViewById<View>(R.id.btnImportarVazio)

                val decor = a.window.decorView
                val decorPos = posicaoNaTela(decor)
                listOf(tvMsg, tvSub, btnEscolher, btnImportar).forEach { v ->
                    val nome = a.resources.getResourceEntryName(v.id)
                    assertTrue("$nome deveria estar visível", v.visibility == View.VISIBLE)
                    assertTrue("$nome deveria ter largura > 0", v.width > 0)
                    assertTrue("$nome deveria ter altura > 0", v.height > 0)
                    val pos = posicaoNaTela(v)
                    assertTrue("$nome cortada à esquerda", pos[0] >= decorPos[0])
                    assertTrue(
                        "$nome cortada à direita",
                        pos[0] + v.width <= decorPos[0] + decor.width
                    )
                }

                val scrollPos = posicaoNaTela(scroll)
                val navPos = posicaoNaTela(nav)
                assertTrue(
                    "ScrollView do vazio invade a barra de navegação",
                    scrollPos[1] + scroll.height <= navPos[1]
                )

                scroll.isSmoothScrollingEnabled = false
                scroll.scrollTo(0, 0)
                val msgPos = posicaoNaTela(tvMsg)
                assertTrue(
                    "Mensagem principal cortada acima do ScrollView",
                    msgPos[1] >= scrollPos[1]
                )

                val fundo = scroll.getChildAt(0)?.height ?: 0
                scroll.scrollTo(0, fundo)
                val btnImportarPos = posicaoNaTela(btnImportar)
                assertTrue(
                    "Botão Importar ficou abaixo da barra de navegação após rolar",
                    btnImportarPos[1] + btnImportar.height <= navPos[1]
                )
            }
        } finally {
            runCatching { device.setOrientationNatural() }
            runCatching { scenario.close() }
        }
    }

    private fun encontrarScrollView(raiz: ViewGroup): ScrollView? {
        for (i in 0 until raiz.childCount) {
            val filho = raiz.getChildAt(i)
            if (filho is ScrollView) return filho
            if (filho is ViewGroup) {
                encontrarScrollView(filho)?.let { return it }
            }
        }
        return null
    }

    private fun posicaoNaTela(view: View): IntArray =
        IntArray(POS_ARRAY_TAMANHO).also { view.getLocationOnScreen(it) }

    private fun esperarNaTela(
        scenario: ActivityScenario<LibraryActivity>,
        descricao: String,
        condicao: (LibraryActivity) -> Boolean
    ) {
        val fim = System.currentTimeMillis() + TIMEOUT_TELA_MS
        while (System.currentTimeMillis() < fim) {
            var pronto = false
            scenario.onActivity { pronto = condicao(it) }
            if (pronto) return
            Thread.sleep(INTERVALO_ESPERA_MS)
        }
        fail("Tempo esgotado: $descricao")
    }
}
