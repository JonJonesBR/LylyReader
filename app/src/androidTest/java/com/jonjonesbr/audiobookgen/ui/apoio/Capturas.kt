package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileOutputStream

/**
 * Capturas de tela e medidas dos testes de interface (rb-46). A pasta vem do argumento `additionalTestOutputDir`, que o
 * AGP puxa para app/build/outputs/connected_android_test_additional_output/; sem ele, ou se ela não puder ser escrita,
 * usa externalMediaDirs (o script do CI puxa essa pasta com adb pull, como plano B).
 */
object Capturas {

    /** Quantas vezes a captura é refeita se a imagem vier toda preta (a tela ainda apagada). */
    private const val RETENTATIVAS_DE_CAPTURA = 3

    /** Espera, antes de cada captura, para a tela acender depois do wakeUp. */
    private const val ESPERA_TELA_ACESA_MS = 300L

    /** Espera entre uma captura preta e a próxima tentativa. */
    private const val ESPERA_ENTRE_CAPTURAS_MS = 500L

    /** Pixels amostrados na diagonal para decidir se a imagem está toda preta. */
    private const val AMOSTRAS_DE_PIXEL = 20

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

    /**
     * Tira uma captura da tela inteira em PNG (`<nome>.png`). Acorda a tela antes e, se a imagem vier toda preta
     * (tela ainda apagada), tenta de novo até [RETENTATIVAS_DE_CAPTURA] vezes. Anota em medidas.txt quantas tentativas
     * foram precisas. Devolve nulo se o sistema não der a imagem.
     */
    fun capturar(nome: String): File? {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentacao)
        var imagem: Bitmap? = null
        var tentativas = 0
        while (tentativas <= RETENTATIVAS_DE_CAPTURA) {
            tentativas++
            device.wakeUp()
            Thread.sleep(ESPERA_TELA_ACESA_MS)
            imagem?.recycle()
            imagem = instrumentacao.uiAutomation.takeScreenshot()
            if (imagem != null && !todaPreta(imagem)) break
            if (tentativas <= RETENTATIVAS_DE_CAPTURA) Thread.sleep(ESPERA_ENTRE_CAPTURAS_MS)
        }
        val bitmap = imagem ?: run {
            anotar(nome, "sem imagem do sistema depois de $tentativas tentativas")
            return null
        }
        val preta = todaPreta(bitmap)
        anotar(nome, "tentativas=$tentativas${if (preta) " ainda toda preta" else ""}")
        val arquivo = File(pasta(), "$nome.png")
        FileOutputStream(arquivo).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return arquivo
    }

    /**
     * Imagem toda preta: todos os pixels amostrados na diagonal são 0x000000 (RGB). Se o Bitmap não puder ser lido
     * (ex.: hardware), devolve falso, para não repetir a captura à toa.
     */
    private fun todaPreta(bitmap: Bitmap): Boolean = runCatching {
        (1..AMOSTRAS_DE_PIXEL).all { i ->
            val x = bitmap.width * i / (AMOSTRAS_DE_PIXEL + 1)
            val y = bitmap.height * i / (AMOSTRAS_DE_PIXEL + 1)
            (bitmap.getPixel(x, y) and 0x00FFFFFF) == 0
        }
    }.getOrDefault(false)

    /** Acrescenta uma linha `[nome] texto` em medidas.txt, na mesma pasta das capturas. */
    fun anotar(nome: String, texto: String) {
        File(pasta(), "medidas.txt").appendText("[$nome] $texto\n")
    }
}
