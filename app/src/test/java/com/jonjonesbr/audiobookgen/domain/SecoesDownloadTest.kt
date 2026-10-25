package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SecoesDownloadTest {

    @Test
    fun `baixando vem antes de qualquer outra secao`() {
        assertEquals(SecaoDownload.BAIXANDO, SecoesDownload.secao(true, false, EstadoDownload.BAIXANDO))
        assertEquals(SecaoDownload.BAIXANDO, SecoesDownload.secao(false, true, EstadoDownload.NA_FILA))
        assertEquals(SecaoDownload.BAIXANDO, SecoesDownload.secao(false, false, EstadoDownload.PAUSADO))
    }

    @Test
    fun `pacote do catalogo vai para no aparelho ou para baixar`() {
        assertEquals(SecaoDownload.NO_APARELHO, SecoesDownload.secao(false, false, EstadoDownload.PRONTO))
        assertEquals(SecaoDownload.PARA_BAIXAR, SecoesDownload.secao(false, false, EstadoDownload.NAO_BAIXADO))
        assertEquals(SecaoDownload.PARA_BAIXAR, SecoesDownload.secao(false, false, EstadoDownload.ERRO))
    }

    @Test
    fun `voz do usuario fica em minhas vozes mesmo pronta`() {
        assertEquals(SecaoDownload.MINHAS_VOZES, SecoesDownload.secao(false, true, EstadoDownload.PRONTO))
    }

    @Test
    fun `nome sem o sufixo tecnico`() {
        assertEquals("Supertonic", SecoesDownload.nomeAmigavel("Supertonic (ONNX local)"))
        assertEquals("Kokoro", SecoesDownload.nomeAmigavel("Kokoro (Sherpa-ONNX local)"))
        assertEquals("Jeff · Piper · pt-BR", SecoesDownload.nomeAmigavel("Jeff · Piper · pt-BR"))
    }

    @Test
    fun `tipo do pacote pelo id`() {
        assertEquals(TipoPacote.CLONADA, SecoesDownload.tipo("pocket-custom-1", false, true))
        assertEquals(TipoPacote.IMPORTADA, SecoesDownload.tipo("byom-x", false, true))
        assertEquals(TipoPacote.POCKET, SecoesDownload.tipo("pocket-ptbr-int8", false, false))
        assertEquals(TipoPacote.SUPERTONIC, SecoesDownload.tipo("supertonic-v1", false, false))
        assertEquals(TipoPacote.PIPER, SecoesDownload.tipo("piper-ptbr-jeff", false, false))
    }

    @Test
    fun `idiomas curtos na ordem pt en es`() {
        assertEquals("PT, EN, ES", SecoesDownload.idiomasCurtos(setOf("es", "en", "pt")))
        assertEquals("PT, DE", SecoesDownload.idiomasCurtos(setOf("de", "pt")))
        assertEquals("", SecoesDownload.idiomasCurtos(emptySet()))
        assertEquals("", SecoesDownload.idiomasCurtos(setOf(IdiomaDownload.MULTILINGUE)))
    }
}
