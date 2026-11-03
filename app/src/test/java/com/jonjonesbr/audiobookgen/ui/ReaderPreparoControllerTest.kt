package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPreparoControllerTest {

    @Test
    fun semLeituraToca() {
        assertEquals(InicioDaEscuta.TOCAR, inicioDaEscuta(-1, pausada = false, tocando = false, carregando = false))
        assertEquals(InicioDaEscuta.TOCAR, inicioDaEscuta(-1, pausada = true, tocando = true, carregando = true))
    }

    @Test
    fun pausadaRetoma() {
        assertEquals(InicioDaEscuta.RETOMAR, inicioDaEscuta(5, pausada = true, tocando = false, carregando = true))
    }

    @Test
    fun tocandoNaoReinicia() {
        assertEquals(InicioDaEscuta.JA_TOCANDO, inicioDaEscuta(5, pausada = false, tocando = true, carregando = false))
    }

    @Test
    fun carregandoNaoReinicia() {
        assertEquals(InicioDaEscuta.JA_TOCANDO, inicioDaEscuta(5, pausada = false, tocando = false, carregando = true))
    }

    @Test
    fun paradaComIndiceToca() {
        assertEquals(InicioDaEscuta.TOCAR, inicioDaEscuta(5, pausada = false, tocando = false, carregando = false))
    }
}
