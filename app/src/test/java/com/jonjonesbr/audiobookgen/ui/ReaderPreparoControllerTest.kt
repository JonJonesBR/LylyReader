package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.service.PreRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReaderPreparoControllerTest {

    @Test
    fun semEstadoNaoHaPreparoEmCurso() {
        assertNull(preparoEmCurso(null))
    }

    @Test
    fun preparoAindaSemTerminarEstaEmCurso() {
        assertNotNull(preparoEmCurso(PreRenderState(feitos = 3, total = 10, titulo = "Cap")))
    }

    @Test
    fun preparoTerminadoOuCanceladoNaoEstaEmCurso() {
        assertNull(preparoEmCurso(PreRenderState(10, 10, "Cap", concluido = true)))
        assertNull(preparoEmCurso(PreRenderState(3, 10, "Cap", cancelado = true)))
        assertNull(preparoEmCurso(PreRenderState(3, 10, "Cap", erro = "falha")))
    }

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
