package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DivisorDeOracoesTest {

    @Test
    fun `divide frases simples terminadas em ponto`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Primeira oração. Segunda oração! Terceira?"
        )
        assertEquals(3, oracoes.size)
        assertEquals("Primeira oração.", oracoes[0].texto)
        assertEquals("Segunda oração!", oracoes[1].texto)
        assertEquals("Terceira?", oracoes[2].texto)
    }

    @Test
    fun `acumula chars das oracoes anteriores para o progresso`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Aaa. Bbbb! Cccccc."
        )
        assertEquals(0, oracoes[0].charsAcumulados)
        assertEquals("Aaa.".length, oracoes[1].charsAcumulados)
        assertEquals("Aaa.".length + "Bbbb!".length, oracoes[2].charsAcumulados)
    }

    @Test
    fun `mantem pontuacao no texto e remove espacos das bordas`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes("  Olá mundo.  Tudo bem?  ")
        assertEquals(2, oracoes.size)
        assertEquals("Olá mundo.", oracoes[0].texto)
        assertEquals("Tudo bem?", oracoes[1].texto)
    }

    @Test
    fun `abreviatura de tratamento nao quebra a frase`() {
        // Antes "Sr." virava unidade própria e o motor (Pocket/Supertonic) lia ruído ou pulava a palavra.
        val oracoes = DivisorDeOracoes.dividirEmOracoes("O Sr. Silva foi. Ele voltou.")
        assertEquals(2, oracoes.size)
        assertEquals("O Sr. Silva foi.", oracoes[0].texto)
        assertEquals("Ele voltou.", oracoes[1].texto)
    }

    @Test
    fun `nao corta ponto decimal`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes("O valor é 1.234,56 reais. Certo?")
        assertEquals(2, oracoes.size)
        assertEquals("O valor é 1.234,56 reais.", oracoes[0].texto)
        assertEquals("Certo?", oracoes[1].texto)
    }

    @Test
    fun `engloba aspas e parenteses de fechamento na oracao`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Ele disse: \"Vamos já!\" E saiu."
        )
        assertEquals(2, oracoes.size)
        assertEquals("Ele disse: \"Vamos já!\"", oracoes[0].texto)
        assertEquals("E saiu.", oracoes[1].texto)
    }

    @Test
    fun `sem pontuacao vira uma oracao unica`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes("texto sem pontuação nenhuma aqui")
        assertEquals(1, oracoes.size)
        assertEquals("texto sem pontuação nenhuma aqui", oracoes[0].texto)
    }

    @Test
    fun `reticencias formam uma oracao`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes("Ele hesitou… e então falou. Fim.")
        assertEquals(3, oracoes.size)
        assertEquals("Ele hesitou…", oracoes[0].texto)
        assertEquals("e então falou.", oracoes[1].texto)
        assertEquals("Fim.", oracoes[2].texto)
    }

    @Test
    fun `texto vazio ou branco retorna lista vazia`() {
        assertTrue(DivisorDeOracoes.dividirEmOracoes("").isEmpty())
        assertTrue(DivisorDeOracoes.dividirEmOracoes("   \n  ").isEmpty())
    }

    @Test
    fun `paragrafo longo real de livro divide em varias oracoes contínuas`() {
        val texto = "NOITE OUTRA VEZ. A Pousada Marco do Percurso estava em silêncio, " +
            "e era um silêncio de três partes. A parte mais óbvia era uma quietude oca " +
            "e repleta de ecos. Dentro da pousada, uma dupla de homens se encolhia num " +
            "canto do bar. Os dois bebiam sem pressa."
        val oracoes = DivisorDeOracoes.dividirEmOracoes(texto)
        assertEquals(5, oracoes.size)
        assertEquals("NOITE OUTRA VEZ.", oracoes[0].texto)
        assertEquals("Os dois bebiam sem pressa.", oracoes.last().texto)
        // A última oração acumula os chars de todas as anteriores.
        val somaChars = oracoes.sumOf { it.texto.length }
        assertTrue(oracoes.last().charsAcumulados + oracoes.last().texto.length <= somaChars)
    }

    @Test
    fun `agrupa oracoes curtas na seguinte`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Oi. Como vai? Tudo bem com você e sua família hoje?"
        )
        // Todas as orações são curtas: agrupa numa unidade única.
        val agrupadas = DivisorDeOracoes.agruparOracoesCurta(oracoes)
        assertEquals(1, agrupadas.size)
        assertEquals("Oi. Como vai? Tudo bem com você e sua família hoje?", agrupadas[0].texto)
    }

    @Test
    fun `agrupa apenas ate o teto e preserva oracoes longas`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Curta um. Curta dois. " +
                "Esta é uma oração longa com conteúdo suficiente para passar do mínimo " +
                "de caracteres e ficar sozinha na unidade. Curta três."
        )
        val agrupadas = DivisorDeOracoes.agruparOracoesCurta(oracoes)
        // As duas curtas iniciais fundem com a longa? Não — a longa não cabe no teto junto
        // com as curtas, então elas fundem entre si até o teto e a longa segue sozinha.
        assertTrue(agrupadas.size <= oracoes.size)
        assertTrue(agrupadas.first().texto.length >= 2)
        assertTrue(agrupadas.any { it.texto.length >= 60 })
    }

    @Test
    fun `agrupamento mantem charsAcumulados coerentes`() {
        val oracoes = DivisorDeOracoes.dividirEmOracoes(
            "Primeira frase com tamanho suficiente. Segunda. Terceira também longa o bastante aqui."
        )
        val agrupadas = DivisorDeOracoes.agruparOracoesCurta(oracoes)
        var esperado = 0
        for (o in agrupadas) {
            assertEquals(esperado, o.charsAcumulados)
            esperado += o.texto.length
        }
    }

    @Test
    fun `nao corta depois de abreviacao de tratamento`() {
        val o = DivisorDeOracoes.dividirEmOracoes("O Dr. Silva chegou cedo. A Sra. Lopes saiu.")
        assertEquals(listOf("O Dr. Silva chegou cedo.", "A Sra. Lopes saiu."), o.map { it.texto })
    }

    @Test
    fun `nao corta depois de inicial solta mas corta fim de frase comum`() {
        val o = DivisorDeOracoes.dividirEmOracoes("Leu J. K. Rowling. Depois dormiu.")
        assertEquals(listOf("Leu J. K. Rowling.", "Depois dormiu."), o.map { it.texto })
    }

    @Test
    fun `abreviacao seguida de maiuscula nao divide a frase`() {
        val o = DivisorDeOracoes.dividirEmOracoes("Ele chamou o Sr. Depois saiu.")
        assertEquals(1, o.size)
    }
}
