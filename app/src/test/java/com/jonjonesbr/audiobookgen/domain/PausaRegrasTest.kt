package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausaRegrasTest {

    @Test
    fun `sem job pausado pode iniciar nova conversao`() {
        assertTrue(PausaRegras.podeIniciarNovaConversao(existeJobPausado = false))
    }

    @Test
    fun `com job pausado nao pode iniciar nova conversao`() {
        assertFalse(PausaRegras.podeIniciarNovaConversao(existeJobPausado = true))
    }
}
