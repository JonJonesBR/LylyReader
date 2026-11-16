package com.jonjonesbr.audiobookgen.util

import android.app.Activity
import android.os.Looper
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jonjonesbr.audiobookgen.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsetsSistemaETecladoTest {

    @Test
    fun aplicaBarrasDeSistemaETecladoPreservandoPaddingBase() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()

        val raiz = LinearLayout(activity).apply {
            setPadding(16, 4, 8, 12)
        }
        activity.setContentView(raiz)
        raiz.aplicarInsetsDeSistemaETeclado()
        shadowOf(Looper.getMainLooper()).idle()

        val soBarras = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 48))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, soBarras)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(16, raiz.paddingLeft)
        assertEquals(64, raiz.paddingTop)
        assertEquals(8, raiz.paddingRight)
        assertEquals(60, raiz.paddingBottom)

        val comTeclado = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 48))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 400))
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, comTeclado)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(412, raiz.paddingBottom)

        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, soBarras)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(60, raiz.paddingBottom)
    }
}
