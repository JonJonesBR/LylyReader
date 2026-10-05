package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.DicasRegras
import com.jonjonesbr.audiobookgen.domain.TelaDica

/**
 * Dicas no momento certo (Fase J): escurece a tela, abre um "buraco" em volta do alvo e mostra um cartão com
 * "Entendi". Sem dependências. A decisão de quando mostrar é de [DicasRegras]; aqui só desenhamos.
 */
object CoachMark {

    private val textos = mapOf(
        Dica.BIB_ADICIONAR to (R.string.dica_bib_adicionar_titulo to R.string.dica_bib_adicionar_texto),
        Dica.BIB_TEMA to (R.string.dica_bib_tema_titulo to R.string.dica_bib_tema_texto),
        Dica.LIVRO_GERAR to (R.string.dica_livro_gerar_titulo to R.string.dica_livro_gerar_texto),
        Dica.LIVRO_VOZ to (R.string.dica_livro_voz_titulo to R.string.dica_livro_voz_texto),
        Dica.DOWNLOADS_VOZES to (R.string.dica_downloads_vozes_titulo to R.string.dica_downloads_vozes_texto),
        Dica.LEITOR_GUIADA to (R.string.dica_leitor_guiada_titulo to R.string.dica_leitor_guiada_texto),
        Dica.LEITOR_VOZ_E_MOTOR to (R.string.dica_leitor_voz_titulo to R.string.dica_leitor_voz_texto)
    )

    /** Mostra a próxima dica da [tela], se houver, apontando para a view que [alvoDe] devolver. */
    fun mostrarProxima(activity: AppCompatActivity, tela: TelaDica, alvoDe: (Dica) -> View?) {
        val ap = AppPrefs(activity)
        val dica = DicasRegras.proxima(
            tela, ap.dicasVistas, ap.onboardingDone, ap.voiceDownloadOnboardingDone, ReleaseNotesDialog.pendente(activity)
        ) ?: return
        mostrar(activity, dica, alvoDe(dica) ?: return)
    }

    /** Mostra uma dica específica (ex.: a da barra da leitura guiada, que só existe depois de iniciar). */
    fun tentar(activity: AppCompatActivity, dica: Dica, alvo: View) {
        val ap = AppPrefs(activity)
        if (!DicasRegras.podeMostrar(
                dica, ap.dicasVistas, ap.onboardingDone, ap.voiceDownloadOnboardingDone, ReleaseNotesDialog.pendente(activity)
            )
        ) return
        mostrar(activity, dica, alvo)
    }

    private fun mostrar(activity: AppCompatActivity, dica: Dica, alvo: View) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val conteudo = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (conteudo.findViewById<View>(R.id.coachMarkRaiz) != null) return // uma por vez
        // Alvo oculto, fora da tela ou cortado: não mostra e não marca como vista (volta na próxima abertura).
        val visivel = Rect()
        if (!alvo.isShown || alvo.width == 0 || alvo.height == 0 || !alvo.getGlobalVisibleRect(visivel)) return
        if (visivel.height() < alvo.height * 0.9f || visivel.width() < alvo.width * 0.9f) return
        val (titulo, texto) = textos[dica] ?: return
        CoachMarkView(activity, dica, alvo, titulo, texto).also { conteudo.addView(it, ViewGroup.LayoutParams(-1, -1)) }
    }

    private class CoachMarkView(
        private val activity: AppCompatActivity,
        private val dica: Dica,
        alvo: View,
        titulo: Int,
        texto: Int
    ) : FrameLayout(activity) {

        private val densidade = resources.displayMetrics.density
        private val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        private val buraco = RectF()
        private val caminho = Path().apply { fillType = Path.FillType.EVEN_ODD }
        private val card: View
        private val voltar = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = fechar()
        }

        init {
            id = R.id.coachMarkRaiz
            setWillNotDraw(false)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 24f * densidade

            card = LayoutInflater.from(activity).inflate(R.layout.view_coach_mark, this, false)
            card.findViewById<TextView>(R.id.tvCoachTitulo).setText(titulo)
            card.findViewById<TextView>(R.id.tvCoachTexto).setText(texto)
            card.findViewById<Button>(R.id.btnCoachEntendi).setOnClickListener { fechar() }
            card.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(card)

            val posAlvo = IntArray(2)
            alvo.getLocationInWindow(posAlvo)
            // Referência = posição desta view na janela (o conteúdo pode ter inset de barras).
            post {
                val minha = IntArray(2)
                getLocationInWindow(minha)
                val margem = 8 * densidade
                buraco.set(
                    posAlvo[0] - minha[0] - margem, posAlvo[1] - minha[1] - margem,
                    posAlvo[0] - minha[0] + alvo.width + margem, posAlvo[1] - minha[1] + alvo.height + margem
                )
                caminho.reset()
                caminho.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                caminho.addRoundRect(buraco, 14 * densidade, 14 * densidade, Path.Direction.CW)
                posicionarCartao()
                invalidate()
            }
            activity.onBackPressedDispatcher.addCallback(activity, voltar)
        }

        private fun posicionarCartao() {
            val lateral = (16 * densidade).toInt()
            val larg = width - 2 * lateral
            card.measure(
                MeasureSpec.makeMeasureSpec(larg, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val alturaCartao = card.measuredHeight
            val folga = (12 * densidade).toInt()
            val acima = buraco.top.toInt() - folga - alturaCartao
            val topo = if (acima >= 0) acima else (buraco.bottom.toInt() + folga)
            val lp = LayoutParams(larg, LayoutParams.WRAP_CONTENT)
            lp.leftMargin = lateral
            lp.topMargin = topo.coerceIn(0, (height - alturaCartao).coerceAtLeast(0))
            card.layoutParams = lp
            card.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }

        override fun onDraw(canvas: Canvas) {
            if (buraco.isEmpty) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pincel)
            else canvas.drawPath(caminho, pincel)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) fechar()
            return true
        }

        private fun fechar() {
            AppPrefs(activity).marcarDicaVista(dica.id)
            voltar.remove()
            (parent as? ViewGroup)?.removeView(this)
        }

        override fun onDetachedFromWindow() {
            voltar.remove()
            super.onDetachedFromWindow()
        }
    }
}
