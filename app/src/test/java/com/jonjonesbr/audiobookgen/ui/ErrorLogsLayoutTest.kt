package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rb-67/19d: a tela de logs na paisagem tem duas colunas (texto e ações à esquerda, lista à direita), e no
 * retrato continua em coluna. Infla o layout com o qualifier de cada orientação e confere os ids e a hierarquia.
 * Sem Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ErrorLogsLayoutTest {

    /** Contexto com o tema do app, criado dentro do teste para pegar o qualifier (orientação) dele. */
    private fun contexto(): Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.AppTheme,
    )

    private fun inflar(ctx: Context): View =
        LayoutInflater.from(ctx).inflate(R.layout.activity_error_logs, FrameLayout(ctx), false)

    @Test
    @Config(qualifiers = "land")
    fun paisagemTemOsMesmosIdsEDuasColunas() {
        val ctx = contexto()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, ctx.resources.configuration.orientation)
        val raiz = inflar(ctx)
        listOf(
            R.id.btnVoltarLogs, R.id.tvTituloLogs, R.id.btnLimparTodosLogs, R.id.tvExplicacaoLogs,
            R.id.tvLinkGithubLogs, R.id.rvErrorLogs, R.id.tvEmptyLogs, R.id.btnEnviarTodosLogs,
        ).forEach { assertNotNull(raiz.findViewById<View>(it)) }

        val lista = raiz.findViewById<View>(R.id.rvErrorLogs)
        val colunaDaLista = lista.parent as View
        val linha = colunaDaLista.parent as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, linha.orientation)
        // À esquerda: texto, link e Enviar todos, na mesma coluna
        val colunaDoTexto = raiz.findViewById<View>(R.id.tvExplicacaoLogs).parent
        assertSame(linha, (colunaDoTexto as View).parent)
        assertSame(colunaDoTexto, raiz.findViewById<View>(R.id.btnEnviarTodosLogs).parent)
        // À direita: a lista e o texto de vazio
        assertSame(colunaDaLista, raiz.findViewById<View>(R.id.tvEmptyLogs).parent)
    }

    @Test
    @Config(qualifiers = "port")
    fun retratoContinuaEmColuna() {
        val ctx = contexto()
        assertEquals(Configuration.ORIENTATION_PORTRAIT, ctx.resources.configuration.orientation)
        val raiz = inflar(ctx) as LinearLayout
        assertEquals(LinearLayout.VERTICAL, raiz.orientation)
        val lista = raiz.findViewById<View>(R.id.rvErrorLogs)
        assertSame(raiz, (lista.parent as View).parent)
    }
}
