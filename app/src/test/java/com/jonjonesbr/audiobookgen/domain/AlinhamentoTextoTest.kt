package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AlinhamentoTextoTest {

    @Test
    fun `sem valor gravado o padrao e justificado`() {
        assertEquals(AlinhamentoTexto.JUSTIFICADO, AlinhamentoTexto.porNome(null))
        assertEquals(AlinhamentoTexto.JUSTIFICADO, AlinhamentoTexto.porNome("qualquer"))
    }

    @Test
    fun `le cada alinhamento pelo nome`() {
        AlinhamentoTexto.entries.forEach { assertEquals(it, AlinhamentoTexto.porNome(it.name)) }
    }
}
