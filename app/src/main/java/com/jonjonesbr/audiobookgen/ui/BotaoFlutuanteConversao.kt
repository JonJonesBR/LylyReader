package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.AreaSegura
import com.jonjonesbr.audiobookgen.domain.LimitesFlutuante
import com.jonjonesbr.audiobookgen.domain.PosicaoFlutuante
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Gerencia o ícone flutuante magnético de conversão em andamento nas telas que não possuem o cartão
 * [CartaoGerandoAgora] (e fora da própria [ConversoesActivity]).
 */
object BotaoFlutuanteConversao {

    private const val MARGEM_BORDA_DP = 12f
    private const val ELEVACAO_Z_DP = 16f
    private const val DURACAO_IMA_MS = 240L
    private const val TENSAO_IMA = 0.85f
    private const val PCT_MAX = 100

    private val TELAS_EXCLUIDAS: Set<Class<out AppCompatActivity>> = setOf(
        ConversoesActivity::class.java,
        MainActivity::class.java,
        OnboardingActivity::class.java,
        EscolhaPrimeiroLivroActivity::class.java,
    )

    fun registrar(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                anexarSeElegivel(app, activity)
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun ehTelaElegivel(activity: Activity): Boolean {
        return activity is AppCompatActivity &&
            activity::class.java !in TELAS_EXCLUIDAS &&
            activity.findViewById<View>(R.id.cartaoGerandoAgora) == null
    }

    private fun anexarSeElegivel(app: Application, activity: Activity) {
        if (!ehTelaElegivel(activity)) return
        val appActivity = activity as AppCompatActivity
        val conteudo = appActivity.findViewById<FrameLayout>(android.R.id.content)
        val existente = conteudo?.findViewById<View>(R.id.botaoFlutuanteConversao)
        if (conteudo == null) {
            Unit
        } else if (existente != null) {
            if (existente.visibility == View.VISIBLE) {
                existente.post { posicionarPelaPreferencia(existente, conteudo, AppPrefs(appActivity)) }
            }
        } else {
            val botao = LayoutInflater.from(appActivity)
                .inflate(R.layout.view_botao_flutuante_conversao, conteudo, false)
            botao.translationZ = ELEVACAO_Z_DP * appActivity.resources.displayMetrics.density
            conteudo.addView(botao)

            val prefs = AppPrefs(appActivity)
            configurarInteracao(appActivity, botao, conteudo, prefs)
            observarConversao(app, appActivity, botao, conteudo, prefs)
        }
    }

    private fun configurarInteracao(
        activity: AppCompatActivity,
        botao: View,
        conteudo: FrameLayout,
        prefs: AppPrefs,
    ) {
        var arrastando = false
        botao.setOnClickListener {
            activity.startActivity(Intent(activity, ConversoesActivity::class.java))
        }
        botao.setOnTouchListener(criarTouchListener(activity, conteudo, prefs) { arrastando = it })
        conteudo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!arrastando && botao.visibility == View.VISIBLE) {
                posicionarPelaPreferencia(botao, conteudo, prefs)
            }
        }
    }

    private fun criarTouchListener(
        activity: AppCompatActivity,
        conteudo: FrameLayout,
        prefs: AppPrefs,
        aoMudarArraste: (Boolean) -> Unit,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var arrastando = false

        return View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = v.x
                    startY = v.y
                    arrastando = false
                    aoMudarArraste(false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!arrastando && PosicaoFlutuante.ehArraste(dx, dy, touchSlop)) {
                        arrastando = true
                        aoMudarArraste(true)
                        conteudo.requestDisallowInterceptTouchEvent(true)
                    }
                    if (arrastando) {
                        val (nx, ny) = PosicaoFlutuante.limitar(startX + dx, startY + dy, limitesDe(v, conteudo))
                        v.x = nx
                        v.y = ny
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    conteudo.requestDisallowInterceptTouchEvent(false)
                    val eraArraste = arrastando
                    arrastando = false
                    aoMudarArraste(false)
                    if (eraArraste) {
                        ancorarComIman(v, conteudo, prefs)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun observarConversao(
        app: Application,
        activity: AppCompatActivity,
        botao: View,
        conteudo: FrameLayout,
        prefs: AppPrefs,
    ) {
        val workManager = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                workManager.getWorkInfosForUniqueWorkFlow(ConversionWorker.WORK_NAME)
                    .filterNotNull()
                    .collect { infos ->
                        val ativo = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                            ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                        if (ativo != null) {
                            val pct = ativo.progress.getInt(ConversionWorker.KEY_PROGRESS_PCT, 0)
                                .coerceIn(0, PCT_MAX)
                            botao.contentDescription =
                                activity.getString(R.string.cd_botao_flutuante_conversao, pct)
                            if (botao.visibility != View.VISIBLE) {
                                botao.visibility = View.VISIBLE
                                botao.post { posicionarPelaPreferencia(botao, conteudo, prefs) }
                            }
                        } else {
                            botao.visibility = View.GONE
                        }
                    }
            }
        }
    }

    private fun posicionarPelaPreferencia(botao: View, conteudo: FrameLayout, prefs: AppPrefs) {
        if (minOf(conteudo.width, conteudo.height, botao.width, botao.height) <= 0) return
        val limites = limitesDe(botao, conteudo)
        botao.x = PosicaoFlutuante.xAncorado(prefs.botaoConversaoBorda, limites)
        botao.y = PosicaoFlutuante.yDeFracao(prefs.botaoConversaoFracaoY, limites)
    }

    private fun ancorarComIman(botao: View, conteudo: FrameLayout, prefs: AppPrefs) {
        if (conteudo.width <= 0 || botao.width <= 0) return
        val limites = limitesDe(botao, conteudo)
        val borda = PosicaoFlutuante.bordaMagnetica(
            x = botao.x,
            larguraBotao = botao.width.toFloat(),
            larguraTela = conteudo.width.toFloat(),
        )
        val destinoX = PosicaoFlutuante.xAncorado(borda, limites)
        val destinoY = botao.y.coerceIn(limites.minY, limites.maxY)
        prefs.botaoConversaoBorda = borda
        prefs.botaoConversaoFracaoY = PosicaoFlutuante.fracaoVertical(destinoY, limites)
        botao.animate()
            .x(destinoX)
            .y(destinoY)
            .setDuration(DURACAO_IMA_MS)
            .setInterpolator(OvershootInterpolator(TENSAO_IMA))
            .start()
    }

    private fun limitesDe(botao: View, conteudo: FrameLayout): LimitesFlutuante {
        val margem = MARGEM_BORDA_DP * conteudo.resources.displayMetrics.density
        val tamanho = maxOf(botao.width, botao.height).toFloat()
        val insets = ViewCompat.getRootWindowInsets(conteudo)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val areaSegura = if (insets != null) {
            AreaSegura(
                esquerda = insets.left.toFloat(),
                topo = insets.top.toFloat(),
                direita = insets.right.toFloat(),
                fundo = insets.bottom.toFloat(),
            )
        } else {
            AreaSegura()
        }
        return PosicaoFlutuante.calcularLimites(
            larguraTela = conteudo.width.toFloat(),
            alturaTela = conteudo.height.toFloat(),
            tamanhoBotao = tamanho,
            margem = margem,
            areaSegura = areaSegura,
        )
    }
}
