package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsperaPrimeiroReadyTest {

    @Test
    fun semItemComecadoNaoHaEspera() {
        val espera = EsperaPrimeiroReady()

        assertFalse(espera.aguardando)
        assertFalse(espera.aoFicarReady())
    }

    @Test
    fun primeiroReadyDisparaBookkeepingSoUmaVez() {
        val espera = EsperaPrimeiroReady()
        espera.comecar()

        assertTrue(espera.aoFicarReady())
        // STATE_READY repetido (ex.: após seek) não pode rodar o início de novo.
        assertFalse(espera.aoFicarReady())
        assertFalse(espera.aguardando)
    }

    @Test
    fun erroAntesDoReadyNaoTrancaAEsperaDoProximoItem() {
        val espera = EsperaPrimeiroReady()
        espera.comecar()
        espera.aoErro()

        assertFalse(espera.aguardando)
        assertTrue(espera.falhaAntesDoPrimeiroReady)

        // Próximo item: a espera funciona normalmente.
        espera.comecar()
        assertTrue(espera.aoFicarReady())
    }

    @Test
    fun retomadaAposErroAntesDoReadyRearmaAEspera() {
        val espera = EsperaPrimeiroReady()
        espera.comecar()
        espera.aoErro()

        espera.aoRetomar()

        assertTrue(espera.aguardando)
        assertTrue(espera.aoFicarReady())
    }

    @Test
    fun erroDepoisDoReadyNaoRearmaAEspera() {
        val espera = EsperaPrimeiroReady()
        espera.comecar()
        assertTrue(espera.aoFicarReady())

        espera.aoErro()
        espera.aoRetomar()

        assertFalse(espera.falhaAntesDoPrimeiroReady)
        assertFalse(espera.aguardando)
        assertFalse(espera.aoFicarReady())
    }

    @Test
    fun retomadaSemFalhaNaoMexeNaEspera() {
        val espera = EsperaPrimeiroReady()

        espera.aoRetomar()

        assertFalse(espera.aguardando)
    }

    @Test
    fun falhasSeguidasAntesDoReadyContinuamRearmando() {
        val espera = EsperaPrimeiroReady()
        espera.comecar()
        espera.aoErro()
        espera.aoRetomar()
        espera.aoErro()

        assertTrue(espera.falhaAntesDoPrimeiroReady)
        espera.aoRetomar()
        assertTrue(espera.aoFicarReady())
    }
}
