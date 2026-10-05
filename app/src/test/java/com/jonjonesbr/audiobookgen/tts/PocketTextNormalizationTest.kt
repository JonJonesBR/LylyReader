package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketTextNormalizationTest {

    @Test
    fun `usa o texto normalizado devolvido pelo normalizador`() {
        val resultado = normalizarParaPocket("Tem 99 anos", "pocket-ptbr-rafael") { texto, _ ->
            texto.replace("99", "noventa e nove")
        }
        assertEquals("Tem noventa e nove anos", resultado)
    }

    @Test
    fun `passa o idioma da voz Pocket ao normalizador`() {
        var idiomaRecebido = ""
        normalizarParaPocket("texto", "pocket-ptbr-rafael") { texto, idioma ->
            idiomaRecebido = idioma
            texto
        }
        assertEquals("pt-BR", idiomaRecebido)
    }

    @Test
    fun `voz desconhecida cai no idioma pt-BR`() {
        var idiomaRecebido = ""
        normalizarParaPocket("texto", "voz-inexistente") { texto, idioma ->
            idiomaRecebido = idioma
            texto
        }
        assertEquals("pt-BR", idiomaRecebido)
    }

    @Test
    fun `resultado vazio do normalizador mantem o texto original`() {
        val resultado = normalizarParaPocket("500 metros", "pocket-ptbr-rafael") { _, _ -> "   " }
        assertEquals("500 metros", resultado)
    }

    @Test
    fun `texto so de simbolos continua vazio para o motor entregar silencio`() {
        val resultado = normalizarParaPocket("***", "pocket-ptbr-rafael") { _, _ -> "" }
        assertEquals("", resultado)
    }

    @Test
    fun `wav de silencio tem cabecalho valido e duracao pedida`() {
        val wav = SilentWav.bytes(durationMs = 400)
        val dados = 24_000 * 400 / 1000 * 2
        assertEquals(44 + dados, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(dados, java.nio.ByteBuffer.wrap(wav, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0, wav.drop(44).count { it != 0.toByte() })
    }
}
