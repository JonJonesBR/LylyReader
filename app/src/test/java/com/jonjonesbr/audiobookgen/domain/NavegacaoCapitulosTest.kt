package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavegacaoCapitulosTest {

    private val capitulos = listOf(
        Capitulo("CAPA PÁGINA", 0, 1),
        Capitulo("DIREITOS AUTORAIS", 2, 4),
        Capitulo("Capítulo 1 — O despertar", 5, 40),
        Capitulo("Capítulo 2 — A Viagem", 41, 90)
    )

    @Test fun `capitulo da posicao de leitura`() {
        assertEquals(0, NavegacaoCapitulos.indiceDoCapitulo(capitulos, 0))
        assertEquals(1, NavegacaoCapitulos.indiceDoCapitulo(capitulos, 3))
        assertEquals(2, NavegacaoCapitulos.indiceDoCapitulo(capitulos, 40))
        assertEquals(3, NavegacaoCapitulos.indiceDoCapitulo(capitulos, 500))
        assertEquals(-1, NavegacaoCapitulos.indiceDoCapitulo(emptyList(), 3))
    }

    @Test fun `titulo em caixa alta vira frase`() {
        assertEquals("Capa página", NavegacaoCapitulos.tituloLimpo("CAPA  PÁGINA "))
        assertEquals("Capítulo IV", NavegacaoCapitulos.tituloLimpo("CAPÍTULO IV"))
        assertEquals("Parte II. O fim", NavegacaoCapitulos.tituloLimpo("PARTE II. O FIM"))
    }

    @Test fun `titulo com minusculas fica como esta`() {
        assertEquals("Capítulo 2 — A Viagem", NavegacaoCapitulos.tituloLimpo("Capítulo 2 — A Viagem"))
        assertEquals("1", NavegacaoCapitulos.tituloLimpo("1"))
    }

    @Test fun `partes tecnicas do livro`() {
        assertTrue(NavegacaoCapitulos.ehParteTecnica("CAPA PÁGINA"))
        assertTrue(NavegacaoCapitulos.ehParteTecnica("Direitos autorais"))
        assertTrue(NavegacaoCapitulos.ehParteTecnica("Sumário"))
        assertTrue(NavegacaoCapitulos.ehParteTecnica("Table of Contents"))
        assertFalse(NavegacaoCapitulos.ehParteTecnica("Capítulo 1 — O despertar"))
        assertFalse(NavegacaoCapitulos.ehParteTecnica("A escapada"))
    }

    @Test fun `busca ignora acentos e maiusculas`() {
        assertEquals(listOf(3), NavegacaoCapitulos.filtrar(capitulos, "viagem"))
        assertEquals(listOf(2, 3), NavegacaoCapitulos.filtrar(capitulos, "CAPITULO"))
        assertEquals(capitulos.indices.toList(), NavegacaoCapitulos.filtrar(capitulos, "  "))
    }
}
