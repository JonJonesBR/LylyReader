package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OcultarSegredosTest {

    @Test
    fun ocultaAChaveNaUrlDoGemini() {
        val msg = "ClientResponseError 400, url='https://x.googleapis.com/v1beta/models/m:generateContent?key=AIzaSyA1234567890abcdefghijklmnopqrs&alt=sse'"
        val limpo = ocultarSegredos(msg)
        assertFalse(limpo.contains("AIzaSy"))
        assertEquals(true, limpo.contains("?key=***&alt=sse"))
    }

    @Test
    fun ocultaCabecalhosEChavesSoltas() {
        assertEquals("Authorization: Bearer ***", ocultarSegredos("Authorization: Bearer sk-or-v1-abcdef0123456789abcdef"))
        assertEquals("{\"xi-api-key\": \"***\"}", ocultarSegredos("{\"xi-api-key\": \"sk_0123456789abcdef0123\"}"))
        assertEquals("chave ***.", ocultarSegredos("chave sk_0123456789abcdef01234567."))
        assertEquals("chave *** no texto", ocultarSegredos("chave AIzaSyA1234567890abcdefghijklmnopqrs no texto"))
    }

    @Test
    fun textoSemChaveFicaIgual() {
        val texto = "Erro ao abrir o livro skate_2024.epub (key frame 3)"
        assertEquals(texto, ocultarSegredos(texto))
    }
}
