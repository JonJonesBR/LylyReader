package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaminhoPermitidoTest {

    private val raizes = listOf(
        "/data/user/0/com.jonjonesbr.audiobookgen/files",
        "/storage/emulated/0/Download",
        "content://media",
    )

    @Test fun arquivoSobFilesDirEhPermitido() {
        assertTrue(caminhoPermitido("/data/user/0/com.jonjonesbr.audiobookgen/files/livro.mp3", raizes))
    }

    @Test fun arquivoNaPastaDownloadsEhPermitido() {
        assertTrue(caminhoPermitido("/storage/emulated/0/Download/Audiobook.mp3", raizes))
    }

    @Test fun uriDoMediaStoreEhPermitida() {
        assertTrue(caminhoPermitido("content://media/external/audio/media/42", raizes))
    }

    @Test fun traversalComDoisPontosEhRejeitado() {
        assertFalse(caminhoPermitido("/data/user/0/com.jonjonesbr.audiobookgen/files/../databases/x.db", raizes))
    }

    @Test fun traversalComBarraInvertidaEhRejeitado() {
        assertFalse(caminhoPermitido("/data/user/0/com.jonjonesbr.audiobookgen/files\\..\\shared_prefs/x.xml", raizes))
    }

    @Test fun traversalNaUriDoMediaStoreEhRejeitado() {
        assertFalse(caminhoPermitido("content://media/../../data/x", raizes))
    }

    @Test fun pastaComPrefixoIgualMasNomeDiferenteEhRejeitada() {
        assertFalse(caminhoPermitido("/data/user/0/com.jonjonesbr.audiobookgen/files2/x.mp3", raizes))
    }

    @Test fun caminhoForaDasRaizesEhRejeitado() {
        assertFalse(caminhoPermitido("/data/data/outro.app/files/x.mp3", raizes))
        assertFalse(caminhoPermitido("/sdcard/DCIM/foto.mp3", raizes))
    }

    @Test fun raizVaziaOuCaminhoVazioEhRejeitado() {
        assertFalse(caminhoPermitido("", raizes))
        assertFalse(caminhoPermitido("   ", raizes))
        assertFalse(caminhoPermitido("/data/x.mp3", listOf("", "/")))
    }

    @Test fun listaVaziaDeRaizesRejeitaTudo() {
        assertFalse(caminhoPermitido("/data/user/0/com.jonjonesbr.audiobookgen/files/livro.mp3", emptyList()))
    }
}
