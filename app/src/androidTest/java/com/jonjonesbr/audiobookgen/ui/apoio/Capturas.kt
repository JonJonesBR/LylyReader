package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Capturas de tela e medidas dos testes de interface (rb-46). A pasta vem do argumento `additionalTestOutputDir`, que o
 * AGP puxa para app/build/outputs/connected_android_test_additional_output/; sem ele, ou se ela não puder ser escrita,
 * usa externalMediaDirs (o script do CI puxa essa pasta com adb pull, como plano B).
 */
object Capturas {

    /**
     * Pasta das capturas e de medidas.txt, criada se faltar. Usa a pasta do AGP quando ela existe e pode ser escrita;
     * senão (armazenamento com escopo no Android 11+), cai para externalMediaDirs.
     */
    fun pasta(): File {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val dirDoAgp = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (dirDoAgp != null) {
            val pastaDoAgp = File(dirDoAgp).apply { mkdirs() }
            if (pastaDoAgp.isDirectory && pastaDoAgp.canWrite()) return pastaDoAgp
        }
        return File(contexto.externalMediaDirs.firstOrNull() ?: contexto.filesDir, "capturas").apply { mkdirs() }
    }

    /** Tira uma captura da tela inteira em PNG (`<nome>.png`). Devolve nulo se o sistema não der a imagem. */
    fun capturar(nome: String): File? {
        val bitmap: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return null
        val arquivo = File(pasta(), "$nome.png")
        FileOutputStream(arquivo).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return arquivo
    }

    /** Acrescenta uma linha `[nome] texto` em medidas.txt, na mesma pasta das capturas. */
    fun anotar(nome: String, texto: String) {
        File(pasta(), "medidas.txt").appendText("[$nome] $texto\n")
    }
}
