package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AlinhamentoTextoTest {

    @Test
    fun `sem valor gravado o padrao e a esquerda`() {
        assertEquals(AlinhamentoTexto.ESQUERDA, AlinhamentoTexto.porNome(null))
        assertEquals(AlinhamentoTexto.ESQUERDA, AlinhamentoTexto.porNome("qualquer"))
    }

    @Test
    fun `le cada alinhamento pelo nome`() {
        AlinhamentoTexto.entries.forEach { assertEquals(it, AlinhamentoTexto.porNome(it.name)) }
    }
}
