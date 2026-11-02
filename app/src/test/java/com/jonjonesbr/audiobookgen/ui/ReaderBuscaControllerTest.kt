package com.jonjonesbr.audiobookgen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBuscaControllerTest {

    // ── contadorDaBusca ───────────────────────────────────────────────────────

    @Test
    fun comResultadosMostraPosicao() {
        // O índice é base zero; o texto mostra "1 de 2" no primeiro achado.
        assertEquals(
            ContadorBusca.Posicao(atual = 1, total = 2),
            contadorDaBusca(total = 2, indiceAtual = 0, consulta = "estrangeiro")
        )
        assertEquals(
            ContadorBusca.Posicao(atual = 2, total = 2),
            contadorDaBusca(total = 2, indiceAtual = 1, consulta = "estrangeiro")
        )
    }

    @Test
    fun consultaSemAchadosMostraSemResultados() {
        assertEquals(ContadorBusca.SemResultados, contadorDaBusca(total = 0, indiceAtual = -1, consulta = "dragão"))
    }

    @Test
    fun semConsultaOculta() {
        assertEquals(ContadorBusca.Oculto, contadorDaBusca(total = 0, indiceAtual = -1, consulta = ""))
    }

    // ── margemDoResultadoBusca ────────────────────────────────────────────────

    @Test
    fun margemVerticalEUmQuartoDaAltura() {
        assertEquals(400, margemDoResultadoBusca(vertical = true, altura = 1600))
        assertEquals(0, margemDoResultadoBusca(vertical = true, altura = 0))
    }

    @Test
    fun horizontalSemMargem() {
        assertEquals(0, margemDoResultadoBusca(vertical = false, altura = 1600))
    }
}
