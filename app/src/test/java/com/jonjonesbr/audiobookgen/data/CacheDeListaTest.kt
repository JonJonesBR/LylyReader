package com.jonjonesbr.audiobookgen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CacheDeListaTest {

    @Test
    fun `mesmo texto devolve a mesma instancia e faz o parse so uma vez`() {
        var chamadas = 0
        val cache = CacheDeLista<String> { raw -> chamadas++; raw.split(",") }

        val primeira = cache.obter("a,b")
        val segunda = cache.obter("a,b")

        assertSame(primeira, segunda)
        assertEquals(1, chamadas)
    }

    @Test
    fun `texto diferente faz novo parse`() {
        var chamadas = 0
        val cache = CacheDeLista<String> { raw -> chamadas++; raw.split(",") }

        cache.obter("a,b")
        val outra = cache.obter("c")

        assertEquals(listOf("c"), outra)
        assertEquals(2, chamadas)
    }

    @Test
    fun `texto vazio passa pelo parse e devolve o resultado dele`() {
        var chamadas = 0
        val cache = CacheDeLista<String> { raw -> chamadas++; if (raw.isEmpty()) emptyList() else listOf(raw) }

        assertEquals(emptyList<String>(), cache.obter(""))
        assertEquals(emptyList<String>(), cache.obter(""))
        assertEquals(1, chamadas)
    }

    @Test
    fun `excecao do parse nao fica no cache e a proxima chamada tenta de novo`() {
        var chamadas = 0
        val cache = CacheDeLista<String> { raw ->
            chamadas++
            if (raw == "ruim") throw IllegalArgumentException("JSON inválido")
            listOf(raw)
        }

        runCatching { cache.obter("ruim") }
        runCatching { cache.obter("ruim") }

        assertEquals(2, chamadas)
        assertEquals(listOf("ok"), cache.obter("ok"))
    }
}
