package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(TipoPacote.PARADEE, SecoesDownload.tipo("paradee-ptbr", false, false))
    }

    @Test
    fun `idiomas curtos na ordem pt en es`() {
        assertEquals("PT, EN, ES", SecoesDownload.idiomasCurtos(setOf("es", "en", "pt")))
        assertEquals("PT, DE", SecoesDownload.idiomasCurtos(setOf("de", "pt")))
        assertEquals("", SecoesDownload.idiomasCurtos(emptySet()))
        assertEquals("", SecoesDownload.idiomasCurtos(setOf(IdiomaDownload.MULTILINGUE)))
    }

    @Test
    fun `motor do pacote e nulo para voz do usuario e livro`() {
        assertEquals("Piper", SecoesDownload.motor(TipoPacote.PIPER))
        assertEquals("Paradee", SecoesDownload.motor(TipoPacote.PARADEE))
        assertEquals("Pocket TTS", SecoesDownload.motor(TipoPacote.POCKET))
        assertNull(SecoesDownload.motor(TipoPacote.CLONADA))
        assertNull(SecoesDownload.motor(TipoPacote.LIVRO))
    }

    @Test
    fun `piper tira o motor e o idioma do nome e junta tamanho na linha 2`() {
        assertEquals(
            LinhasVoz("Jeff", "Piper · leve e rápida · PT · 81 MB"),
            SecoesDownload.linhasVoz("Jeff · Piper · pt-BR", "Piper", "leve e rápida", "PT", "81 MB")
        )
    }

    @Test
    fun `pocket sem sobra no nome fica com o motor`() {
        assertEquals(
            LinhasVoz("Pocket TTS", "Pocket TTS · expressiva, permite clonar · PT · 120 MB"),
            SecoesDownload.linhasVoz(
                "Pocket TTS · Português do Brasil", "Pocket TTS", "expressiva, permite clonar", "PT", "120 MB"
            )
        )
    }

    @Test
    fun `sem o idioma na linha 2 o nome fica com o idioma`() {
        assertEquals(
            LinhasVoz("Supertonic (English)", "Supertonic · natural voices"),
            SecoesDownload.linhasVoz("Supertonic (English)", "Supertonic", "natural voices", null, null)
        )
        assertEquals(
            LinhasVoz("Português do Brasil", "Pocket TTS · expressiva, permite clonar · 120 MB"),
            SecoesDownload.linhasVoz(
                "Pocket TTS · Português do Brasil", "Pocket TTS", "expressiva, permite clonar", null, "120 MB"
            )
        )
    }

    @Test
    fun `supertonic em ingles tira o idioma entre parenteses`() {
        assertEquals(
            LinhasVoz("Supertonic", "Supertonic · natural voices · EN"),
            SecoesDownload.linhasVoz("Supertonic (English)", "Supertonic", "natural voices", "EN", null)
        )
        assertEquals("Kokoro", SecoesDownload.linhasVoz("Kokoro", "Kokoro", "many voices", null, null).nome)
    }

    @Test
    fun `voz do usuario mantem o nome e usa a descricao no lugar do motor`() {
        assertEquals(
            LinhasVoz("Cadu", "Sua voz clonada · 480 kB"),
            SecoesDownload.linhasVoz("Cadu", null, "Sua voz clonada", null, "480 kB")
        )
    }

    @Test
    fun `idioma so aparece quando nao e nulo e tamanho so na voz pronta`() {
        assertEquals(
            LinhasVoz("Jeff · pt-BR", "Piper · leve e rápida"),
            SecoesDownload.linhasVoz("Jeff · Piper · pt-BR", "Piper", "leve e rápida", null, null)
        )
        assertEquals(
            LinhasVoz("Jeff", "Piper · leve e rápida · PT"),
            SecoesDownload.linhasVoz("Jeff · Piper · pt-BR", "Piper", "leve e rápida", "PT", "")
        )
    }

    @Test
    fun `livro sem motor mantem o nome`() {
        assertEquals(
            LinhasVoz("Dom Casmurro · English", "PT"),
            SecoesDownload.linhasVoz("Dom Casmurro · English", null, null, "PT", null)
        )
    }
}
