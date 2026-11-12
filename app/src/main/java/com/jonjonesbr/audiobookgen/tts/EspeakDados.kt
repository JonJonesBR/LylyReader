package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Dados do espeak-ng 1.52.0 (assets `espeak-ng-paradee/`) copiados para o disco, porque o espeak lê
 * os arquivos por caminho. A cópia fica pronta só quando o marcador `.completo` existe.
 */
internal object EspeakDados {
    private const val VERSAO = "espeak-ng-data-1.52.0"
    private const val ASSETS = "espeak-ng-paradee"
    private const val MARCADOR = ".completo"
    private val ARQUIVOS = listOf(
        "phontab",
        "phonindex",
        "phondata",
        "intonations",
        "pt_dict",
        "en_dict",
        "lang/roa/pt-BR",
    )
    private val trava = Any()

    /** Garante a cópia em `filesDir/paradee/espeak-ng-data-1.52.0/` e devolve a pasta. */
    fun garantir(context: Context): File {
        synchronized(trava) {
            val destino = File(context.filesDir, "paradee/$VERSAO")
            if (File(destino, MARCADOR).isFile) return destino

            // Copia para uma pasta temporária e só então renomeia: nunca fica uma cópia pela metade no destino.
            val temp = File(context.filesDir, "paradee/$VERSAO.tmp")
            temp.deleteRecursively()
            temp.mkdirs()
            for (nome in ARQUIVOS) {
                val saida = File(temp, nome)
                saida.parentFile?.mkdirs()
                context.assets.open("$ASSETS/$nome").use { entrada ->
                    saida.outputStream().use { entrada.copyTo(it) }
                }
            }
            destino.deleteRecursively()
            if (!temp.renameTo(destino)) {
                throw IOException("Não foi possível preparar os dados do espeak-ng em ${destino.absolutePath}")
            }
            File(destino, MARCADOR).createNewFile()
            return destino
        }
    }
}
