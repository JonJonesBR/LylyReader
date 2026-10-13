package com.jonjonesbr.audiobookgen.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diz se alguma tela do app está visível (activity iniciada e ainda não parada).
 * Usado pela conversão para decidir se pode entrar em primeiro plano no Android 12+.
 */
object VisibilidadeDoApp {

    private val activitiesIniciadas = AtomicInteger(0)

    /** true enquanto houver ao menos uma activity iniciada. */
    val appVisivel: Boolean
        get() = activitiesIniciadas.get() > 0

    /** Instante (ms) da última vez em que uma activity iniciou ou parou; 0 se nunca. Reinicia o orçamento de primeiro plano. */
    @Volatile
    var ultimaVisivelMs: Long = 0L
        private set

    /** Registra os callbacks de ciclo de vida da [app]. Chamar uma vez no `onCreate` da Application. */
    fun registrar(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activitiesIniciadas.incrementAndGet()
                ultimaVisivelMs = System.currentTimeMillis()
            }

            override fun onActivityStopped(activity: Activity) {
                activitiesIniciadas.updateAndGet { if (it > 0) it - 1 else 0 }
                ultimaVisivelMs = System.currentTimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
