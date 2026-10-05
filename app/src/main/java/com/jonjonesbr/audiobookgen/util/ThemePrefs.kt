package com.jonjonesbr.audiobookgen.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.jonjonesbr.audiobookgen.R
import java.util.WeakHashMap

/**
 * Preferência de tema do app: seguir o sistema, Claro, Escuro ou Papel (creme de página de livro).
 * Claro/Escuro/Sistema usam [AppCompatDelegate.setDefaultNightMode]; o Papel é um tema claro próprio
 * (`AppTheme.Papel`), aplicado em cada Activity por [aplicarNaActivity] antes do `onCreate`.
 */
object ThemePrefs {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
    const val PAPEL = 3

    private const val PREFS = "audiobookgen_prefs"
    private const val KEY = "tema_app"

    fun load(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SYSTEM).takeIf { it in SYSTEM..PAPEL } ?: SYSTEM

    fun save(ctx: Context, modo: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, modo).apply()
    }

    private fun nightMode(modo: Int): Int = when (modo) {
        LIGHT, PAPEL -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Aplica o modo (chamar no startup com [load], ou ao trocar nas Configurações). */
    fun apply(modo: Int) {
        AppCompatDelegate.setDefaultNightMode(nightMode(modo))
    }

    /** Chamar como PRIMEIRA linha do `onCreate` de cada Activity (antes de `super.onCreate`): põe o tema Papel, se for o escolhido. */
    fun aplicarNaActivity(activity: Activity) {
        if (load(activity) == PAPEL) activity.setTheme(R.style.AppTheme_Papel)
    }

    /**
     * Recria sozinhas as Activities que ficaram abertas com um tema antigo quando o usuário troca o tema em outra tela
     * (o `recreate()` da tela atual não alcança as que estão na pilha).
     */
    fun instalar(app: Application) {
        val modoAoCriar = WeakHashMap<Activity, Int>()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                modoAoCriar[activity] = load(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                val criadaCom = modoAoCriar[activity] ?: return
                if (criadaCom != load(activity)) {
                    modoAoCriar[activity] = load(activity)
                    activity.recreate()
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
