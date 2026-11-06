package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorSoNaSobreposicao
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * rb-67 (item 5): na busca de livros, a raiz tem fitsSystemWindows e já põe a lista acima da barra de navegação.
 * Então a lista (recyclerLivros) não soma a barra de novo: o padding de baixo continua igual ao do XML.
 * Usa uma Activity vazia com o layout inflado, porque a BuscarLivrosActivity começa a busca na rede ao abrir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsetsDaBuscaDeLivrosTest {

    private companion object {
        const val NAVEGACAO_PX = 48
    }

    @Test
    fun raizComFitsSystemWindowsNaoSomaABarraDeNavegacaoAoPaddingDaLista() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()

        val pai = FrameLayout(activity)
        activity.setContentView(pai)
        LayoutInflater.from(activity).inflate(R.layout.activity_buscar_livros, pai, true)
        shadowOf(Looper.getMainLooper()).idle()

        val lista = activity.findViewById<View>(R.id.recyclerLivros)
        val paddingDoXml = lista.paddingBottom
        lista.aplicarPaddingInferiorSoNaSobreposicao()

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, NAVEGACAO_PX))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVEGACAO_PX))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a lista somou a barra de navegação ao padding, mesmo com a raiz em fitsSystemWindows",
            paddingDoXml,
            lista.paddingBottom,
        )
    }
}
