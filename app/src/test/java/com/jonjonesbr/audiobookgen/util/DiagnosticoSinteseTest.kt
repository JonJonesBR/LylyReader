package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticoSinteseTest {

    @Test
    fun linhaTemOsCamposEOEstadoDoAparelho() {
        val linha = DiagnosticoSintese.montarLinha(
            hora = "10:00:00.000", processo = 7, assunto = "trecho",
            campos = listOf("chars" to 80, "ritmo" to 13.456f, "texto" to "Olá mundo", "nulo" to null),
            aparelho = "bat=35.0C"
        )
        assertEquals("10:00:00.000 p7 trecho chars=80 ritmo=13.46 texto=\"Olá mundo\" nulo=- | bat=35.0C\n", linha)
    }

    @Test
    fun aspasEQuebrasNoTextoNaoQuebramALinha() {
        val linha = DiagnosticoSintese.montarLinha("h", 1, "t", listOf("texto" to "disse \"oi\"\nfim"), "")
        assertEquals(1, linha.count { it == '\n' })
        assertTrue(linha.contains("\"disse 'oi' fim\""))
    }

    @Test
    fun aparaMantemSoOFinalComecandoNumaLinhaInteira() {
        val conteudo = "linha1\nlinha2\nlinha3\n"
        assertEquals("linha3\n", DiagnosticoSintese.aparar(conteudo, 9))
        assertEquals(conteudo, DiagnosticoSintese.aparar(conteudo, 100))
    }
}
