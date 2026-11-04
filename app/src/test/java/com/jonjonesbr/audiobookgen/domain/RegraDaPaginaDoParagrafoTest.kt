package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegraDaPaginaDoParagrafoTest {

    // Altura visível de 1000 px: a linha de destaque fica em 420 px (42%).
    private val altura = 1000

    @Test
    fun alturaZeroNaoRola() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(900, 0))
    }

    @Test
    fun linhaAbaixoDoAlvoMasNoLimiarNaoRola() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(300, altura))
        assertEquals(0, RegraDaPaginaDoParagrafo.passoParaDestaque(426, altura))
    }

    @Test
    fun logoAcimaDoLimiarRolaOPassoMinimo() {
        assertEquals(1, RegraDaPaginaDoParagrafo.passoParaDestaque(427, altura))
    }

    @Test
    fun linhaDentroDaAreaRolaEmPasso() {
        assertEquals(90, RegraDaPaginaDoParagrafo.passoParaDestaque(920, altura))
    }

    @Test
    fun linhaForaDaAreaVaiDireto() {
        assertEquals(1080, RegraDaPaginaDoParagrafo.passoParaDestaque(1500, altura))
    }

    @Test
    fun linhaAcimaDoTopoVoltaDireto() {
        assertEquals(-620, RegraDaPaginaDoParagrafo.passoParaDestaque(-200, altura))
    }

    @Test
    fun baseDaLinhaNoTopoExatoVoltaDireto() {
        // A base da linha em 0 já está fora da área (acima do topo): salta direto para 42%.
        assertEquals(-420, RegraDaPaginaDoParagrafo.passoParaDestaque(0, altura))
    }

    @Test
    fun baseDaLinhaNaBordaDeBaixoRolaEmPasso() {
        // Exatamente na borda da área ainda conta como dentro: rola em passo, não salta.
        assertEquals(104, RegraDaPaginaDoParagrafo.passoParaDestaque(1000, altura))
    }

    @Test
    fun paginaSemRolagemRegistradaNaoRolaSozinha() {
        // rb-60: sem nenhuma rolagem marcada, o toque é de texto (a página nunca rolou).
        assertFalse(RegraDaPaginaDoParagrafo.rolandoSozinha(1_000L, null))
        assertFalse(RegraDaPaginaDoParagrafo.rolandoSozinha(0L, null))
    }

    @Test
    fun rolagemRecenteContaAteOLimiar() {
        // rb-60: rolou agora, e ainda dentro do limiar: rola sozinha.
        assertTrue(RegraDaPaginaDoParagrafo.rolandoSozinha(1_000L, 1_000L))
        assertTrue(RegraDaPaginaDoParagrafo.rolandoSozinha(1_047L, 1_000L))
        // No limiar (48 ms) ou depois, já parou.
        assertFalse(RegraDaPaginaDoParagrafo.rolandoSozinha(1_048L, 1_000L))
        assertFalse(RegraDaPaginaDoParagrafo.rolandoSozinha(2_000L, 1_000L))
    }

    @Test
    fun mesmoParagrafoLinhaAcimaDoTopoNaoSobe() {
        // rb-65: a linha que volta para cima no mesmo parágrafo não puxa a lista para cima.
        assertEquals(0, RegraDaPaginaDoParagrafo.passoComHisterese(-200, altura, false))
        assertEquals(0, RegraDaPaginaDoParagrafo.passoComHisterese(0, altura, false))
    }

    @Test
    fun paragrafoNovoLinhaAcimaDoTopoSobe() {
        // rb-62: no primeiro acompanhamento de um parágrafo novo, a linha acima do topo sobe direto.
        assertEquals(-620, RegraDaPaginaDoParagrafo.passoComHisterese(-200, altura, true))
    }

    @Test
    fun abaixoDaAreaDesceSempre() {
        assertEquals(1080, RegraDaPaginaDoParagrafo.passoComHisterese(1500, altura, false))
        assertEquals(1080, RegraDaPaginaDoParagrafo.passoComHisterese(1500, altura, true))
    }

    @Test
    fun dentroDaAreaPassoIgualNosDoisCasos() {
        assertEquals(90, RegraDaPaginaDoParagrafo.passoComHisterese(920, altura, false))
        assertEquals(90, RegraDaPaginaDoParagrafo.passoComHisterese(920, altura, true))
    }

    @Test
    fun pertoDoAlvoNaoRolaNosDoisCasos() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoComHisterese(300, altura, false))
        assertEquals(0, RegraDaPaginaDoParagrafo.passoComHisterese(300, altura, true))
    }

    @Test
    fun alturaZeroNaoRolaComHisterese() {
        assertEquals(0, RegraDaPaginaDoParagrafo.passoComHisterese(900, 0, false))
    }
}
