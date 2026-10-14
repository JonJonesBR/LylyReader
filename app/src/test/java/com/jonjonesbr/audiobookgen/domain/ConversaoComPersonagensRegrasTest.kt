package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversaoComPersonagensRegrasTest {

    @Test
    fun semVozesAtribuidasNaoUsaOMapa() {
        assertFalse(ConversaoComPersonagensRegras.usarMapa(false, true, true, "epub"))
    }

    @Test
    fun semTextoDoMapaNaoUsaOMapa() {
        assertFalse(ConversaoComPersonagensRegras.usarMapa(true, false, true, "pdf"))
    }

    @Test
    fun epubSemCapitulosNoMapaNaoUsaOMapa() {
        assertFalse(ConversaoComPersonagensRegras.usarMapa(true, true, false, "epub"))
    }

    @Test
    fun epubEmMaiusculoSemCapitulosNoMapaNaoUsaOMapa() {
        assertFalse(ConversaoComPersonagensRegras.usarMapa(true, true, false, "EPUB"))
    }

    @Test
    fun txtEMdComCapitulosNoMapaUsamOMapa() {
        assertTrue(ConversaoComPersonagensRegras.usarMapa(true, true, true, "txt"))
        assertTrue(ConversaoComPersonagensRegras.usarMapa(true, true, true, "md"))
    }

    @Test
    fun soEpubTxtEMdTemCapitulosNaExtracao() {
        assertTrue(ConversaoComPersonagensRegras.formatoTemCapitulosNaExtracao("EPUB"))
        assertTrue(ConversaoComPersonagensRegras.formatoTemCapitulosNaExtracao("md"))
        assertFalse(ConversaoComPersonagensRegras.formatoTemCapitulosNaExtracao("pdf"))
        assertFalse(ConversaoComPersonagensRegras.formatoTemCapitulosNaExtracao("mobi"))
    }

    @Test
    fun pdfEDocxSemCapitulosNoMapaUsamOMapa() {
        assertTrue(ConversaoComPersonagensRegras.usarMapa(true, true, false, "pdf"))
        assertTrue(ConversaoComPersonagensRegras.usarMapa(true, true, false, "docx"))
    }
}
