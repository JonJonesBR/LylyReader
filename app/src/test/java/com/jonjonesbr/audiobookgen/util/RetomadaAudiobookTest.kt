package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RetomadaAudiobookTest {

    private val umaHora = 3_600_000

    @Test
    fun semPosicaoSalvaComecaDoInicio() {
        assertEquals(0, posicaoDeRetomada(null, umaHora, 0))
    }

    @Test
    fun quaseNoInicioOuQuaseNoFimComecaDoInicio() {
        assertEquals(0, posicaoDeRetomada(2_000, umaHora, 0))
        assertEquals(0, posicaoDeRetomada(umaHora - 5_000, umaHora, 0))
        assertEquals(0, posicaoDeRetomada(umaHora, umaHora, 0))
    }

    @Test
    fun noMeioContinuaDeOndeParouMenosORecuo() {
        assertEquals(600_000, posicaoDeRetomada(600_000, umaHora, 0))
        assertEquals(570_000, posicaoDeRetomada(600_000, umaHora, 30_000))
    }

    @Test
    fun recuoNuncaPassaDoComeco() {
        assertEquals(0, posicaoDeRetomada(4_000, umaHora, 30_000))
    }
}
