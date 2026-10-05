package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChavesDeLivroTest {

    @Test
    fun `copia o valor do caminho antigo para o novo sem apagar o antigo`() {
        val r = ChavesDeLivro.migrarMapa(mapOf("/a" to "voz|motor"), "/a", "/b")
        assertEquals(mapOf("/a" to "voz|motor", "/b" to "voz|motor"), r)
    }

    @Test
    fun `usa o prefixo da chave`() {
        val r = ChavesDeLivro.migrarMapa(mapOf("reader_scroll_/a" to 40, "outra" to 1), "/a", "/b", "reader_scroll_")
        assertEquals(40, r["reader_scroll_/b"])
        assertEquals(1, r["outra"])
    }

    @Test
    fun `nunca sobrescreve o que ja existe no caminho novo`() {
        val r = ChavesDeLivro.migrarMapa(mapOf("/a" to "velho", "/b" to "atual"), "/a", "/b")
        assertEquals("atual", r["/b"])
    }

    @Test
    fun `resolver de posicao fica com a maior`() {
        val mapa = mapOf("s_/a" to 80, "s_/b" to 30)
        assertEquals(80, ChavesDeLivro.migrarMapa(mapa, "/a", "/b", "s_", ChavesDeLivro.maiorInteiro)["s_/b"])
        val inverso = mapOf("s_/a" to 10, "s_/b" to 30)
        assertEquals(30, ChavesDeLivro.migrarMapa(inverso, "/a", "/b", "s_", ChavesDeLivro.maiorInteiro)["s_/b"])
    }

    @Test
    fun `sem a chave antiga ou com caminhos iguais devolve o mesmo mapa`() {
        val mapa = mapOf("/x" to 1)
        assertSame(mapa, ChavesDeLivro.migrarMapa(mapa, "/a", "/b"))
        assertSame(mapa, ChavesDeLivro.migrarMapa(mapa, "/x", "/x"))
    }

    @Test
    fun `lista troca so os itens do livro certo`() {
        val itens = listOf("/a:1", "/b:2", "/a:3")
        val r = ChavesDeLivro.migrarLista(itens, "/a", "/c", { it.substringBefore(':') }) { item, novo -> novo + ":" + item.substringAfter(':') }
        assertEquals(listOf("/c:1", "/b:2", "/c:3"), r)
    }

    @Test
    fun `caminhos concluidos trocam sem repetir`() {
        assertEquals(listOf("/novo", "/b"), ChavesDeLivro.migrarCaminhos(listOf("/a", "/b"), "/a", "/novo"))
        assertEquals(listOf("/novo"), ChavesDeLivro.migrarCaminhos(listOf("/a", "/novo"), "/a", "/novo"))
        assertEquals(listOf("/b"), ChavesDeLivro.migrarCaminhos(listOf("/b"), "/a", "/novo"))
    }
}
