package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DicaDeBateriaTest {

    @Test
    fun `android 6 com otimizacao ativada e dica nunca vista deve sugerir`() {
        assertTrue(DicaDeBateria.deveSugerir(sdk = 23, otimizacaoIgnorada = false, jaVista = false))
    }

    @Test
    fun `android 5 nao deve sugerir mesmo com otimizacao ativada`() {
        assertFalse(DicaDeBateria.deveSugerir(sdk = 22, otimizacaoIgnorada = false, jaVista = false))
    }

    @Test
    fun `sem restricao de bateria nao deve sugerir`() {
        assertFalse(DicaDeBateria.deveSugerir(sdk = 36, otimizacaoIgnorada = true, jaVista = false))
    }

    @Test
    fun `dica ja vista nao deve sugerir de novo`() {
        assertFalse(DicaDeBateria.deveSugerir(sdk = 36, otimizacaoIgnorada = false, jaVista = true))
    }

    @Test
    fun `ignorada e ja vista ao mesmo tempo nao deve sugerir`() {
        assertFalse(DicaDeBateria.deveSugerir(sdk = 36, otimizacaoIgnorada = true, jaVista = true))
    }
}
