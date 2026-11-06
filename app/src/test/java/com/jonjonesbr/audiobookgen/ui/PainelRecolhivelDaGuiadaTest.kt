package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Painel de baixo da guiada recolhível (rb-66, passo 5). Infla o `painel_guiada` (retrato e paisagem) sem Activity e
 * confere o que [PainelRecolhivelDaGuiada] mostra e esconde pelas tags, a seta, o play da tira e o espelho do play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PainelRecolhivelDaGuiadaTest {

    private val contexto: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.AppTheme,
    )

    private fun inflar(): View {
        val pai = FrameLayout(contexto)
        return LayoutInflater.from(contexto).inflate(R.layout.painel_guiada, pai, false)
    }

    /** As views da árvore que têm a tag dada. */
    private fun comTag(raiz: View, tag: String): List<View> {
        val achadas = mutableListOf<View>()
        fun percorrer(v: View) {
            if (v.tag == tag) achadas.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        percorrer(raiz)
        return achadas
    }

    private fun conferirAberto(raiz: View, aberto: Boolean) {
        val estado = if (aberto) "aberto" else "recolhido"
        val soAberto = comTag(raiz, "so_aberto")
        val soRecolhido = comTag(raiz, "so_recolhido")
        assertTrue(soAberto.isNotEmpty())
        soAberto.forEach { v ->
            val nome = v.resources.getResourceEntryName(v.id)
            assertEquals("$nome com o painel $estado", visibilidade(aberto), v.visibility)
        }
        soRecolhido.forEach { v ->
            val nome = v.resources.getResourceEntryName(v.id)
            assertEquals("$nome com o painel $estado", visibilidade(!aberto), v.visibility)
        }
    }

    private fun visibilidade(mostrar: Boolean): Int = if (mostrar) View.VISIBLE else View.GONE

    @Test
    fun retratoRecolhidoMostraATiraEEscondeOsControles() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        painel.aplicar(aberto = false, seta = true)
        conferirAberto(raiz, aberto = false)
        assertEquals(View.VISIBLE, raiz.findViewById<View>(R.id.btnGuidedRecolher).visibility)
        assertEquals(View.VISIBLE, raiz.findViewById<View>(R.id.btnGuidedPlayPauseTira).visibility)
        assertEquals(View.VISIBLE, raiz.findViewById<View>(R.id.progressoGuiadaTira).visibility)
    }

    @Test
    fun retratoAbertoMostraOPainelDeHoje() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        painel.aplicar(aberto = false, seta = true)
        painel.aplicar(aberto = true, seta = true)
        conferirAberto(raiz, aberto = true)
        assertEquals(View.GONE, raiz.findViewById<View>(R.id.btnGuidedPlayPauseTira).visibility)
        assertEquals(View.GONE, raiz.findViewById<View>(R.id.progressoGuiadaTira).visibility)
    }

    @Test
    fun semSetaEsconde() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        painel.aplicar(aberto = false, seta = false)
        assertEquals(View.GONE, raiz.findViewById<View>(R.id.btnGuidedRecolher).visibility)
        conferirAberto(raiz, aberto = false)
    }

    @Test
    fun setaTrocaIconeEDescricaoConformeOEstado() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        val seta = raiz.findViewById<ImageButton>(R.id.btnGuidedRecolher)
        painel.aplicar(aberto = true, seta = true)
        assertEquals(contexto.getString(R.string.cd_guided_recolher_controles), seta.contentDescription.toString())
        assertEquals(contexto.getString(R.string.cd_guided_recolher_controles), seta.tooltipText.toString())
        painel.aplicar(aberto = false, seta = true)
        assertEquals(contexto.getString(R.string.cd_guided_mostrar_controles), seta.contentDescription.toString())
        assertEquals(contexto.getString(R.string.cd_guided_mostrar_controles), seta.tooltipText.toString())
    }

    @Test
    fun setaMandaMostrarQuandoRecolhidoERecolherQuandoAberto() {
        val raiz = inflar()
        val gestos = mutableListOf<GestoDasBarras>()
        val painel = PainelRecolhivelDaGuiada(raiz) { gestos.add(it) }
        painel.ligar()
        val seta = raiz.findViewById<ImageButton>(R.id.btnGuidedRecolher)
        painel.aplicar(aberto = false, seta = true)
        seta.performClick()
        painel.aplicar(aberto = true, seta = true)
        seta.performClick()
        assertEquals(listOf(GestoDasBarras.MOSTRAR, GestoDasBarras.RECOLHER), gestos)
    }

    @Test
    fun playDaTiraChamaOPlayPrincipal() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        var cliquesNoPrincipal = 0
        raiz.findViewById<View>(R.id.btnGuidedPlayPause).setOnClickListener { cliquesNoPrincipal++ }
        raiz.findViewById<View>(R.id.btnGuidedPlayPauseTira).performClick()
        assertEquals(1, cliquesNoPrincipal)
    }

    @Test
    fun espelharNaTiraAcompanhaOPlayEOProgresso() {
        val raiz = inflar()
        raiz.espelharNaTira(tocando = true, descricao = "Pausar", indice = 3, total = 10)
        val tira = raiz.findViewById<ImageButton>(R.id.btnGuidedPlayPauseTira)
        val progresso = raiz.findViewById<LinearProgressIndicator>(R.id.progressoGuiadaTira)
        assertEquals("Pausar", tira.contentDescription.toString())
        assertEquals(10, progresso.max)
        assertEquals(3, progresso.progress)
        raiz.espelharNaTira(tocando = false, descricao = "Tocar", indice = 42, total = 0)
        assertEquals("Tocar", tira.contentDescription.toString())
        assertEquals(1, progresso.max)
        assertEquals(1, progresso.progress)
    }

    @Test
    @Config(qualifiers = "land")
    fun paisagemRecolhidaMantemOPlayESetaESemTira() {
        val raiz = inflar()
        val painel = PainelRecolhivelDaGuiada(raiz) {}
        painel.ligar()
        painel.aplicar(aberto = false, seta = true)
        conferirAberto(raiz, aberto = false)
        // Na paisagem o play da linha é o da tira: não há tira nem progresso fino.
        assertNull(raiz.findViewById<View>(R.id.btnGuidedPlayPauseTira))
        assertNull(raiz.findViewById<View>(R.id.progressoGuiadaTira))
        assertNotNull(raiz.findViewById<View>(R.id.btnGuidedPlayPause))
        assertEquals(View.VISIBLE, raiz.findViewById<View>(R.id.btnGuidedPlayPause).visibility)
        assertEquals(View.VISIBLE, raiz.findViewById<View>(R.id.btnGuidedRecolher).visibility)
    }
}
