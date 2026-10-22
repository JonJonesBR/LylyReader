package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextoComMarcacaoTest {

    @Test
    fun `texto sem marcadores vira um trecho normal`() {
        assertEquals(listOf(Trecho(TipoTrecho.NORMAL, "Era uma vez.")), TextoComMarcacao.analisar("Era uma vez."))
    }

    @Test
    fun `separa negrito e dica de entonacao`() {
        val trechos = TextoComMarcacao.analisar("Em **domingo**, _(tom calmo)_ ela riu.")
        assertEquals(
            listOf(
                Trecho(TipoTrecho.NORMAL, "Em "),
                Trecho(TipoTrecho.NEGRITO, "domingo"),
                Trecho(TipoTrecho.NORMAL, ", "),
                Trecho(TipoTrecho.DICA, "tom calmo"),
                Trecho(TipoTrecho.NORMAL, " ela riu."),
            ),
            trechos,
        )
    }

    @Test
    fun `negrito no inicio e no fim nao deixa trecho vazio`() {
        val trechos = TextoComMarcacao.analisar("**Sol**")
        assertEquals(listOf(Trecho(TipoTrecho.NEGRITO, "Sol")), trechos)
    }

    @Test
    fun `marcador sem fechamento e tratado como texto`() {
        assertEquals("Ela disse **oi sem fechar.", TextoComMarcacao.semMarcacao("Ela disse **oi sem fechar."))
        assertEquals("Ela disse _(oi", TextoComMarcacao.semMarcacao("Ela disse _(oi"))
    }

    @Test
    fun `marcador solto sem par seguinte fica como texto`() {
        assertEquals(listOf(Trecho(TipoTrecho.NORMAL, "a ** b")), TextoComMarcacao.analisar("a ** b"))
    }

    @Test
    fun `marcador solto antes de um par nao apaga o texto`() {
        // O parser pareia da esquerda para a direita: o conteúdo pode mudar, mas nada se perde nem quebra.
        assertTrue(TextoComMarcacao.semMarcacao("a ** b **forte**").contains("forte"))
    }

    @Test
    fun `sem marcacao remove os marcadores e mantem o texto que sera lido`() {
        val texto = "Joana **sorriu**. _(afirmação)_ Fim."
        assertEquals("Joana sorriu. afirmação Fim.", TextoComMarcacao.semMarcacao(texto))
    }

    @Test
    fun `texto vazio nao gera trechos`() {
        assertEquals(emptyList<Trecho>(), TextoComMarcacao.analisar(""))
    }
}
