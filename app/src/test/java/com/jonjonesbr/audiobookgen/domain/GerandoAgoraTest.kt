package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GerandoAgoraTest {

    @Test
    fun `conversao rodando tem prioridade sobre pausada e fila`() {
        val estado = GerandoAgora.de(true, "Dom Casmurro", 42, "Outro" to 10, 3)
        assertEquals(GerandoAgora.Gerando("Dom Casmurro", 42), estado)
    }

    @Test
    fun `sem nome a conversao rodando aparece sem titulo`() {
        assertEquals(GerandoAgora.Gerando(null, 0), GerandoAgora.de(true, " ", -5, null, 0))
    }

    @Test
    fun `pausada aparece quando nada roda`() {
        assertEquals(GerandoAgora.Pausada("Livro", 100), GerandoAgora.de(false, null, 0, "Livro" to 130, 2))
    }

    @Test
    fun `fila parada aparece com a quantidade`() {
        assertEquals(GerandoAgora.NaFila(2), GerandoAgora.de(false, null, 0, null, 2))
    }

    @Test
    fun `sem nada o cartao some`() {
        assertEquals(GerandoAgora.Nada, GerandoAgora.de(false, null, 0, null, 0))
    }
}
