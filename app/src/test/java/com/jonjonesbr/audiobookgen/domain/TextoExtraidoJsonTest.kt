package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextoExtraidoJsonTest {

    private val texto = TextoExtraido(
        capitulos = listOf(Capitulo("Um \"começo\"", 0, 1), Capitulo("Dois", 2, 2)),
        paragrafos = listOf(Paragrafo("Olá,\nmundo", 0, 0), Paragrafo("— Fala", 12, 0), Paragrafo("Fim", 20, 1)),
        totalChars = 23
    )

    @Test
    fun `cache ida e volta preserva tudo`() {
        val json = TextoExtraidoJson.paraCache(texto, removidas = 7)
        assertEquals(texto, TextoExtraidoJson.doCache(json))
        assertEquals(7, TextoExtraidoJson.removidasDoCache(json))
    }

    @Test
    fun `le o formato da extracao do python`() {
        val json = """
            {"ok": true, "total_chars": 9,
             "capitulos": [{"titulo": "", "inicio": 0, "fim": 0}],
             "paragrafos": [{"texto": "Oi", "char_offset": 0, "capitulo_idx": 0}]}
        """.trimIndent()
        val lido = TextoExtraidoJson.daExtracao(json, "Sem título").getOrThrow()
        assertEquals("Sem título", lido.capitulos.single().titulo)
        assertEquals(Paragrafo("Oi", 0, 0), lido.paragrafos.single())
        assertEquals(9, lido.totalChars)
    }

    @Test
    fun `erro da extracao vira falha com a mensagem`() {
        val resultado = TextoExtraidoJson.daExtracao("""{"ok": false, "erro": "PDF escaneado"}""", "x")
        assertTrue(resultado.isFailure)
        assertEquals("PDF escaneado", resultado.exceptionOrNull()?.message)
    }
}
