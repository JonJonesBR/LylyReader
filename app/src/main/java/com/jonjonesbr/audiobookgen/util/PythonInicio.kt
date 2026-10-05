package com.jonjonesbr.audiobookgen.util

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Ponto único para iniciar o Python (Chaquopy) no processo principal. Qualquer código que chame `Python.getInstance()`
 * sem ter passado antes pelo motor de conversão (ex.: o download de pacotes Piper, que extrai o arquivo em Python) precisa
 * chamar [garantir] antes: sem isso a primeira abertura do app falhava com "Cannot use GenericPlatform on Android".
 * A trava evita a segunda `start` ("Python already started") quando duas telas/tarefas pedem ao mesmo tempo.
 */
object PythonInicio {
    private val trava = Any()

    fun garantir(context: Context) {
        synchronized(trava) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        }
    }
}
