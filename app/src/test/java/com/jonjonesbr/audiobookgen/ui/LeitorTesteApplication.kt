package com.jonjonesbr.audiobookgen.ui

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * Substitui a [com.jonjonesbr.audiobookgen.LylyApplication] nos testes Robolectric. A de produção inicializa motores,
 * cópia de modelos, bridges e threads de cache, nada disso cabe na JVM. Aqui só sobe o WorkManager de teste, que o
 * AudiobookViewModel usa mesmo no modo "Ler sem áudio".
 */
class LeitorTesteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        )
    }
}
