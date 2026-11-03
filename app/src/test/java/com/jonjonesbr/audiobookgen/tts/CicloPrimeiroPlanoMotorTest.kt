package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CicloPrimeiroPlanoMotorTest {

    @Test
    fun `a primeira sintese pede primeiro plano e a segunda em voo nao`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar())
        ciclo.confirmarPrimeiroPlano(true)
        assertFalse(ciclo.aoComecar())
        assertEquals(2, ciclo.emVoo)
    }

    @Test
    fun `falha com sintese em voo faz a seguinte pedir primeiro plano de novo`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar())
        ciclo.confirmarPrimeiroPlano(false)
        assertTrue(ciclo.aoComecar())
        assertEquals(2, ciclo.emVoo)
    }

    @Test
    fun `so a ultima sintese devolve ficha para agendar a saida`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        ciclo.aoComecar()
        ciclo.confirmarPrimeiroPlano(true)
        ciclo.aoComecar()
        assertNull(ciclo.aoTerminar())
        assertNotNull(ciclo.aoTerminar())
    }

    @Test
    fun `espera vencida sem sintese nova tira do primeiro plano`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        ciclo.aoComecar()
        ciclo.confirmarPrimeiroPlano(true)
        val ficha = ciclo.aoTerminar()!!
        assertTrue(ciclo.aoVencerEspera(ficha))
        assertFalse(ciclo.emPrimeiroPlano)
    }

    @Test
    fun `sintese nova durante a espera mantem o primeiro plano`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        ciclo.aoComecar()
        ciclo.confirmarPrimeiroPlano(true)
        val fichaAntiga = ciclo.aoTerminar()!!

        // Começo novo durante a espera: já está em primeiro plano, não precisa chamar startForeground.
        assertFalse(ciclo.aoComecar())
        assertFalse(ciclo.aoVencerEspera(fichaAntiga))
        assertTrue(ciclo.emPrimeiroPlano)

        // Quando essa síntese terminar, a nova espera usa a ficha atual.
        val fichaNova = ciclo.aoTerminar()!!
        assertTrue(ciclo.aoVencerEspera(fichaNova))
        assertFalse(ciclo.emPrimeiroPlano)
    }

    @Test
    fun `falha do primeiro plano nao fica marcada e a proxima sintese tenta de novo`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar())
        ciclo.confirmarPrimeiroPlano(false)
        assertNull(ciclo.aoTerminar())
        assertTrue(ciclo.aoComecar())
    }

    @Test
    fun `saida forcada faz a proxima sintese pedir primeiro plano de novo`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        ciclo.aoComecar()
        ciclo.confirmarPrimeiroPlano(true)
        ciclo.aoTerminar()
        ciclo.aoSairForcado()
        assertFalse(ciclo.emPrimeiroPlano)
        assertTrue(ciclo.aoComecar())
    }

    @Test
    fun `terminar a mais nao deixa emVoo negativo`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertNull(ciclo.aoTerminar())
        assertEquals(0, ciclo.emVoo)
    }
}
