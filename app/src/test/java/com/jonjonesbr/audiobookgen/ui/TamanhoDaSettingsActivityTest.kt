package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Teto de tamanho da SettingsActivity (rb-11). Os Ajustes foram divididos em controllers (ui/Ajustes*Controller);
 * este teste é o que avisa se a Activity voltar a crescer. Lógica nova vai para um controller, não para a Activity.
 */
class TamanhoDaSettingsActivityTest {

    @Test
    fun settingsActivityNaoPassaDe600Linhas() {
        val arquivo = localizarArquivo()
        val linhas = arquivo.readLines().size
        assertTrue(
            "SettingsActivity tem $linhas linhas (teto: $LIMITE_LINHAS). Lógica nova vai para um controller " +
                "(ui/Ajustes*Controller), não para a Activity.",
            linhas <= LIMITE_LINHAS,
        )
    }

    /** O Gradle roda os testes com o diretório do módulo `app`; a raiz do repositório também é aceita. */
    private fun localizarArquivo(): File {
        val relativo = "src/main/java/com/jonjonesbr/audiobookgen/ui/SettingsActivity.kt"
        val candidatos = listOf(File(relativo), File("app/$relativo"))
        return candidatos.firstOrNull { it.isFile }
            ?: error("SettingsActivity.kt não encontrado. Procurado em: ${candidatos.map { it.absolutePath }}")
    }

    private companion object {
        const val LIMITE_LINHAS = 600
    }
}
