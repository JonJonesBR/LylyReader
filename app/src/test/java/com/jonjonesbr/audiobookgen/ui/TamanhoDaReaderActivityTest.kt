package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Teto de tamanho da ReaderActivity. O detekt já mede a classe (LargeClass, limite de 600 linhas), mas o baseline
 * dela a silencia; por isso este teste é o que avisa se a Activity voltar a crescer. Lógica nova vai para um
 * controller (ui/Reader*Controller) ou para o ReaderViewModel (rb-10), não para a Activity.
 */
class TamanhoDaReaderActivityTest {

    @Test
    fun readerActivityNaoPassaDe1000Linhas() {
        val arquivo = localizarArquivo()
        val linhas = arquivo.readLines().size
        assertTrue(
            "ReaderActivity tem $linhas linhas (teto: $LIMITE_LINHAS). Lógica nova vai para um controller " +
                "(ui/Reader*Controller) ou para o ReaderViewModel, não para a Activity.",
            linhas <= LIMITE_LINHAS,
        )
    }

    /** O Gradle roda os testes com o diretório do módulo `app`; a raiz do repositório também é aceita. */
    private fun localizarArquivo(): File {
        val relativo = "src/main/java/com/jonjonesbr/audiobookgen/ui/ReaderActivity.kt"
        val candidatos = listOf(File(relativo), File("app/$relativo"))
        return candidatos.firstOrNull { it.isFile }
            ?: error("ReaderActivity.kt não encontrado. Procurado em: ${candidatos.map { it.absolutePath }}")
    }

    private companion object {
        const val LIMITE_LINHAS = 1000
    }
}
