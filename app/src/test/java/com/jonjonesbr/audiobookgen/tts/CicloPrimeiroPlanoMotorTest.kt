package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jonjonesbr.audiobookgen.tts.TipoPrimeiroPlanoMotor.DADOS
import com.jonjonesbr.audiobookgen.tts.TipoPrimeiroPlanoMotor.REPRODUCAO

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

    @Test
    fun `tipo e mediaPlayback so na guiada a partir do Android 15`() {
        assertEquals(DADOS, tipoDoPrimeiroPlanoMotor(34, true))
        assertEquals(REPRODUCAO, tipoDoPrimeiroPlanoMotor(35, true))
        assertEquals(DADOS, tipoDoPrimeiroPlanoMotor(35, false))
        assertEquals(REPRODUCAO, tipoDoPrimeiroPlanoMotor(36, true))
    }

    @Test
    fun `guiada que comeca com o servico em dataSync troca para mediaPlayback`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar(DADOS))
        ciclo.confirmarPrimeiroPlano(true, DADOS)
        assertTrue(ciclo.aoComecar(REPRODUCAO))
        ciclo.confirmarPrimeiroPlano(true, REPRODUCAO)
        assertEquals(REPRODUCAO, ciclo.tipoAtual)
        assertFalse(ciclo.aoComecar(REPRODUCAO))
    }

    @Test
    fun `guiada que acaba troca mediaPlayback de volta para dataSync`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar(REPRODUCAO))
        ciclo.confirmarPrimeiroPlano(true, REPRODUCAO)
        assertTrue(ciclo.aoComecar(DADOS))
    }

    @Test
    fun `falha na troca de tipo mantem o tipo anterior`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar(DADOS))
        ciclo.confirmarPrimeiroPlano(true, DADOS)
        assertTrue(ciclo.aoComecar(REPRODUCAO))
        ciclo.confirmarPrimeiroPlano(false, REPRODUCAO)
        assertTrue(ciclo.emPrimeiroPlano)
        assertEquals(DADOS, ciclo.tipoAtual)
        // As duas sínteses terminam: a saída continua agendada pela ficha.
        assertNull(ciclo.aoTerminar())
        assertNotNull(ciclo.aoTerminar())
        assertTrue(ciclo.aoComecar(REPRODUCAO))
    }

    @Test
    fun `depois do timeout a falha nao agenda saida`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar(DADOS))
        ciclo.confirmarPrimeiroPlano(true, DADOS)
        ciclo.aoSairForcado()
        assertTrue(ciclo.aoComecar(DADOS))
        ciclo.confirmarPrimeiroPlano(false, DADOS)
        assertFalse(ciclo.emPrimeiroPlano)
        assertNull(ciclo.aoTerminar())
    }

    @Test
    fun `depois do timeout a reproducao volta a entrar`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        assertTrue(ciclo.aoComecar(DADOS))
        ciclo.confirmarPrimeiroPlano(true, DADOS)
        ciclo.aoSairForcado()
        assertTrue(ciclo.aoComecar(REPRODUCAO))
        ciclo.confirmarPrimeiroPlano(true, REPRODUCAO)
        assertEquals(REPRODUCAO, ciclo.tipoAtual)
    }

    @Test
    fun `espera vencida e saida forcada zeram o tipo`() {
        val ciclo = CicloPrimeiroPlanoMotor()
        ciclo.aoComecar(REPRODUCAO)
        ciclo.confirmarPrimeiroPlano(true, REPRODUCAO)
        val ficha = ciclo.aoTerminar()!!
        assertTrue(ciclo.aoVencerEspera(ficha))
        assertNull(ciclo.tipoAtual)

        ciclo.aoComecar(REPRODUCAO)
        ciclo.confirmarPrimeiroPlano(true, REPRODUCAO)
        ciclo.aoSairForcado()
        assertNull(ciclo.tipoAtual)
    }
}
