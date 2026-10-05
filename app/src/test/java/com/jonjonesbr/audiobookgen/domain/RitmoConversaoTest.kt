package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RitmoConversaoTest {
    @Test
    fun `posicao e ritmo sao inversos dentro da faixa`() {
        for (ritmo in RITMO_CONVERSAO_MIN..RITMO_CONVERSAO_MAX) assertEquals(ritmo, ritmoDaPosicao(posicaoDoRitmo(ritmo)))
    }

    @Test
    fun `extremos do seekbar viram os limites do ritmo`() {
        assertEquals(50, ritmoDaPosicao(0))
        assertEquals(200, ritmoDaPosicao(150))
    }

    @Test
    fun `valores fora da faixa sao limitados`() {
        assertEquals(0, posicaoDoRitmo(10))
        assertEquals(150, posicaoDoRitmo(900))
        assertEquals(200, ritmoDaPosicao(999))
        assertEquals(50, ritmoDaPosicao(-20))
    }
}
