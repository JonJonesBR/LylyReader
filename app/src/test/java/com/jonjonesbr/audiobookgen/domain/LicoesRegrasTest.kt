package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicoesRegrasTest {
    @Test
    fun `sem eventos nenhuma licao esta concluida`() {
        val estado = LicoesRegras.estado(emptySet())
        assertEquals(5, estado.size)
        assertTrue(estado.none { it.concluida })
        assertEquals(0, LicoesRegras.concluidas(emptySet()))
        assertEquals(Licao.ADICIONAR, LicoesRegras.proximaPendente(emptySet()))
    }

    @Test
    fun `evento conclui so a propria licao`() {
        val eventos = setOf("audiobook_gerado")
        val estado = LicoesRegras.estado(eventos).associate { it.licao to it.concluida }
        assertEquals(true, estado[Licao.GERAR])
        assertEquals(false, estado[Licao.GUIADA])
        assertEquals(1, LicoesRegras.concluidas(eventos))
    }

    @Test
    fun `proxima pendente pula as concluidas`() {
        assertEquals(Licao.GUIADA, LicoesRegras.proximaPendente(setOf("livro_importado")))
        assertEquals(Licao.ADICIONAR, LicoesRegras.proximaPendente(setOf("leitura_guiada_iniciada")))
    }

    @Test
    fun `tudo concluido nao tem proxima`() {
        val todos = EventoAprendizado.values().map { it.id }.toSet()
        assertEquals(5, LicoesRegras.concluidas(todos))
        assertNull(LicoesRegras.proximaPendente(todos))
    }

    @Test
    fun `evento desconhecido e ignorado`() {
        assertEquals(0, LicoesRegras.concluidas(setOf("qualquer_coisa", "")))
    }

    @Test
    fun `ids dos eventos sao unicos`() {
        assertEquals(EventoAprendizado.values().size, EventoAprendizado.values().map { it.id }.toSet().size)
    }
}
