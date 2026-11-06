package com.jonjonesbr.audiobookgen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CopiaExportadaStoreTest {

    @Test fun apagaSoAsChavesCujoArquivoInternoSumiu() {
        val existentes = setOf("/data/files/a.mp3", "/data/files/c.mp3")
        val orfas = CopiaExportadaStore.chavesOrfas(
            listOf("/data/files/a.mp3", "/data/files/b.mp3", "/data/files/c.mp3")
        ) { it in existentes }
        assertEquals(listOf("/data/files/b.mp3"), orfas)
    }

    @Test fun semChavesNaoHaOrfas() {
        assertEquals(emptyList<String>(), CopiaExportadaStore.chavesOrfas(emptyList()) { false })
    }

    @Test fun todasOrfasQuandoNenhumArquivoExiste() {
        val chaves = listOf("/data/files/a.mp3", "/data/files/b.mp3")
        assertEquals(chaves, CopiaExportadaStore.chavesOrfas(chaves) { false })
    }
}
