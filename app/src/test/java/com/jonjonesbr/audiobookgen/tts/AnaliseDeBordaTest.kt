package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AnaliseDeBordaTest {

    private val taxa = 44100

    private fun tom(ms: Int): FloatArray = FloatArray(taxa * ms / 1000) { i ->
        (0.5 * sin(2 * PI * 440 * i / taxa)).toFloat()
    }

    private fun silencio(ms: Int): FloatArray = FloatArray(taxa * ms / 1000)

    private fun juntar(vararg partes: FloatArray): FloatArray =
        partes.fold(FloatArray(0)) { acumulado, parte -> acumulado + parte }

    @Test fun `silencio no inicio e medido ate a primeira fala`() {
        val r = AnaliseDeBorda.analisar(juntar(silencio(200), tom(1000), silencio(300)), taxa)
        assertTrue("inicio ${r.inicioMs}", r.inicioMs in 190..210)
        assertTrue("fim ${r.fimMs}", r.fimMs in 290..310)
    }

    @Test fun `dois pulsos separados por silencio curto no inicio sao suspeita de duplicado`() {
        val r = AnaliseDeBorda.analisar(juntar(tom(150), silencio(60), tom(150), silencio(300)), taxa)
        assertEquals(1, r.valesNoInicio)
        assertEquals(150, r.primeiroValeMs)
        assertTrue(r.suspeitaDeInicioDuplicado)
    }

    @Test fun `tom continuo nao tem vale nem suspeita`() {
        val r = AnaliseDeBorda.analisar(tom(1000), taxa)
        assertEquals(0, r.valesNoInicio)
        assertNull(r.primeiroValeMs)
        assertFalse(r.suspeitaDeInicioDuplicado)
    }

    @Test fun `audio vazio ou mudo devolve tudo zerado`() {
        val zerado = ResultadoDeBorda(0, 0, 0, null, false)
        assertEquals(zerado, AnaliseDeBorda.analisar(FloatArray(0), taxa))
        assertEquals(zerado, AnaliseDeBorda.analisar(silencio(500), taxa))
    }
}
