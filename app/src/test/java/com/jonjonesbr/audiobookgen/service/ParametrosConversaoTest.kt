package com.jonjonesbr.audiobookgen.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParametrosConversaoTest {

    @Test
    fun `serializacao preserva todos os campos obrigatorios e opcionais`() {
        val original = ParametrosConversao(
            arquivoEntrada = "/tmp/livro.epub",
            voz = "pt-BR-AntonioNeural",
            velocidade = "+10%",
            estilo = "padrao",
            caminhoSaidaInterna = "/data/user/0/app/files/livro.mp3",
            motor = "edge",
            runToken = "tok-123",
            livroOrigemCaminho = "/books/livro.epub",
            speakerMapPath = "/tmp/speakers.json",
            tomMeiosTons = -2,
            agudosDb = 6,
            filaItemId = "fila-1",
            livroDicionarioCaminho = "/books/livro.epub",
        )
        val restaurado = ParametrosConversao.fromJson(original.toJson())
        assertEquals(original, restaurado)
    }

    @Test
    fun `serializacao preserva campos opcionais nulos`() {
        val original = ParametrosConversao(
            arquivoEntrada = "/tmp/trecho.txt",
            voz = "supertonic-m1-pt",
            velocidade = "+0%",
            estilo = "padrao",
            caminhoSaidaInterna = "/data/user/0/app/files/trecho.mp3",
            motor = "supertonic",
        )
        val restaurado = ParametrosConversao.fromJson(original.toJson())
        assertEquals(original, restaurado)
    }

    @Test
    fun `fromJson devolve nulo para entrada vazia ou invalida`() {
        assertNull(ParametrosConversao.fromJson(null))
        assertNull(ParametrosConversao.fromJson(""))
        assertNull(ParametrosConversao.fromJson("{invalido"))
        assertNull(ParametrosConversao.fromJson("""{"arquivoEntrada":""}"""))
    }
}
