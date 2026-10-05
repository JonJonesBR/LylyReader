package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivroDeExemploRegrasTest {
    @Test
    fun `biblioteca vazia sem filtro oferece o exemplo`() {
        assertTrue(LivroDeExemploRegras.deveOferecer(totalLivros = 0, filtroAtivo = false, exemploPresente = false))
    }

    @Test
    fun `vazio por causa de busca ou aba nao oferece`() {
        assertFalse(LivroDeExemploRegras.deveOferecer(totalLivros = 0, filtroAtivo = true, exemploPresente = false))
    }

    @Test
    fun `biblioteca com livros nao oferece`() {
        assertFalse(LivroDeExemploRegras.deveOferecer(totalLivros = 3, filtroAtivo = false, exemploPresente = false))
    }

    @Test
    fun `exemplo ja presente nao oferece de novo`() {
        assertFalse(LivroDeExemploRegras.deveOferecer(totalLivros = 0, filtroAtivo = false, exemploPresente = true))
    }
}
