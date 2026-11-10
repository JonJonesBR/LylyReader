package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualidadeDaVozTest {

    @Test
    fun `presets do Supertonic e do Pocket`() {
        assertEquals(8, QualidadeDaVoz.RAPIDA.supertonicPassos)
        assertEquals(10, QualidadeDaVoz.EQUILIBRADA.supertonicPassos)
        assertEquals(14, QualidadeDaVoz.ALTA.supertonicPassos)
        assertEquals(6, QualidadeDaVoz.RAPIDA.pocketPassos)
        assertEquals(10, QualidadeDaVoz.EQUILIBRADA.pocketPassos)
        assertEquals(12, QualidadeDaVoz.ALTA.pocketPassos)
    }

    @Test
    fun `preset mais proximo do Supertonic`() {
        assertEquals(QualidadeDaVoz.RAPIDA, QualidadeDaVoz.maisProximaDeSupertonic(2))
        assertEquals(QualidadeDaVoz.RAPIDA, QualidadeDaVoz.maisProximaDeSupertonic(9)) // empate 8/10: menor
        assertEquals(QualidadeDaVoz.EQUILIBRADA, QualidadeDaVoz.maisProximaDeSupertonic(11))
        assertEquals(QualidadeDaVoz.EQUILIBRADA, QualidadeDaVoz.maisProximaDeSupertonic(12)) // empate 10/14: menor
        assertEquals(QualidadeDaVoz.ALTA, QualidadeDaVoz.maisProximaDeSupertonic(18))
        assertEquals(QualidadeDaVoz.ALTA, QualidadeDaVoz.maisProximaDeSupertonic(20))
    }

    @Test
    fun `preset mais proximo do Pocket`() {
        assertEquals(QualidadeDaVoz.RAPIDA, QualidadeDaVoz.maisProximaDePocket(1))
        assertEquals(QualidadeDaVoz.RAPIDA, QualidadeDaVoz.maisProximaDePocket(8)) // empate 6/10: menor
        assertEquals(QualidadeDaVoz.EQUILIBRADA, QualidadeDaVoz.maisProximaDePocket(11)) // empate 10/12: menor
        assertEquals(QualidadeDaVoz.ALTA, QualidadeDaVoz.maisProximaDePocket(20))
    }

    @Test
    fun `exibida usa o Pocket so para o motor pocket`() {
        assertEquals(QualidadeDaVoz.ALTA, QualidadeDaVoz.exibida("pocket", supertonicPassos = 10, pocketPassos = 20))
        assertEquals(QualidadeDaVoz.RAPIDA, QualidadeDaVoz.exibida("onnx", supertonicPassos = 8, pocketPassos = 20))
    }

    @Test
    fun `so a Alta avisa que pode pausar`() {
        assertFalse(QualidadeDaVoz.RAPIDA.avisaPausar)
        assertFalse(QualidadeDaVoz.EQUILIBRADA.avisaPausar)
        assertTrue(QualidadeDaVoz.ALTA.avisaPausar)
    }
}
