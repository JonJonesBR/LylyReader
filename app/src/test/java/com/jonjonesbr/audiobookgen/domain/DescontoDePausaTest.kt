package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DescontoDePausaTest {

    @Test fun `pausa menor que o atraso vira zero`() {
        assertEquals(0, PausasNaturais.descontar(100, 368L))
    }

    @Test fun `o atraso sai da pausa planejada`() {
        assertEquals(282, PausasNaturais.descontar(650, 368L))
    }

    @Test fun `sem atraso estimado a pausa fica como foi planejada`() {
        assertEquals(500, PausasNaturais.descontar(500, 0L))
    }
}
