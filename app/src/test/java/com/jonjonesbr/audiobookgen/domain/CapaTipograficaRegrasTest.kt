package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapaTipograficaRegrasTest {

    @Test
    fun `a cor e sempre da paleta e estavel para o mesmo id`() {
        listOf("a", "72e463696e08fc3b", "", "gutenberg-1", "ÿ").forEach { id ->
            assertTrue(corDaCapa(id) in PALETA_CAPAS)
            assertEquals(corDaCapa(id), corDaCapa(id))
        }
    }

    @Test
    fun `ids diferentes usam mais de uma cor`() {
        val cores = (1..40).map { corDaCapa("livro-$it") }.toSet()
        assertTrue(cores.size > 3)
    }

    @Test
    fun `titulo mais longo usa letra menor`() {
        assertTrue(tamanhoRelativoDoTitulo("Dom Casmurro") > tamanhoRelativoDoTitulo("A".repeat(40)))
        assertTrue(tamanhoRelativoDoTitulo("A".repeat(40)) > tamanhoRelativoDoTitulo("A".repeat(70)))
    }
}
