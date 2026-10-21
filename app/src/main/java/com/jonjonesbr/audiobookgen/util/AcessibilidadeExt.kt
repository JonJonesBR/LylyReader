package com.jonjonesbr.audiobookgen.util

import android.view.View
import android.widget.Button
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.jonjonesbr.audiobookgen.R

/**
 * Anuncia um contêiner clicável (LinearLayout, FrameLayout, CardView...) ao TalkBack como botão.
 * Sem isso ele é lido só como texto, sem papel. Não muda clique nem aparência.
 * Substitui o delegate de acessibilidade que a view já tiver, se tiver.
 */
fun View.marcarComoBotao() {
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    })
}

/**
 * Anuncia a view ao TalkBack como link (ex.: créditos em Ajustes). Papel via roleDescription,
 * porque não existe className de link. Não muda clique nem aparência.
 */
fun View.marcarComoLink() {
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.roleDescription = host.context.getString(R.string.acessibilidade_papel_link)
        }
    })
}
