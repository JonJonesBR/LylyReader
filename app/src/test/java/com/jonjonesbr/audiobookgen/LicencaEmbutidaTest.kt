package com.jonjonesbr.audiobookgen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O texto da GPL-3.0 que o app mostra (Ajustes > Sobre > licença) é a cópia embutida nos assets. Ela tem de ser
 * idêntica ao LICENSE da raiz, para que o app traga o texto oficial e não uma versão editada ou desatualizada.
 */
class LicencaEmbutidaTest {

    @Test
    fun textoEmbutidoDaGplEIgualAoLicenseDaRaiz() {
        val licenca = localizar("src/main/assets/licencas/GPL-3.0.txt", "app/src/main/assets/licencas/GPL-3.0.txt")
        val raiz = localizar("LICENSE", "../LICENSE")
        assertTrue(
            "copie o LICENSE para assets/licencas/GPL-3.0.txt",
            licenca.readBytes().contentEquals(raiz.readBytes()),
        )
    }

    /** O Gradle roda os testes com o diretório do módulo `app`; a raiz do repositório também é aceita. */
    private fun localizar(vararg candidatos: String): File {
        val arquivos = candidatos.map { File(it) }
        return arquivos.firstOrNull { it.isFile }
            ?: error("Arquivo não encontrado. Procurado em: ${arquivos.map { it.absolutePath }}")
    }
}
