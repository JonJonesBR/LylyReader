package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlDeBuscaTest {

    @Test
    fun espacoViraMaisEAcentoVaiCodificado() {
        assertEquals(
            "https://www.google.com/search?q=significado+de+S%C3%A3o+Paulo",
            urlDeBuscaDoSignificado("significado de São Paulo"),
        )
    }

    @Test
    fun caracteresReservadosSaoCodificados() {
        assertEquals("https://www.google.com/search?q=a%26b%3Dc", urlDeBuscaDoSignificado("a&b=c"))
    }

    @Test
    fun palavraSimplesFicaIgual() {
        assertEquals("https://www.google.com/search?q=casa", urlDeBuscaDoSignificado("casa"))
    }
}
