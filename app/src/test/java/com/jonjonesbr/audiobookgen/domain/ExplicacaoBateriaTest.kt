package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicacaoBateriaTest {

    @Test
    fun `xiaomi em qualquer caixa e os irmaos redmi e poco contam como xiaomi`() {
        assertTrue(ExplicacaoBateria.ehXiaomi("Xiaomi"))
        assertTrue(ExplicacaoBateria.ehXiaomi("XIAOMI"))
        assertTrue(ExplicacaoBateria.ehXiaomi("Redmi"))
        assertTrue(ExplicacaoBateria.ehXiaomi("POCO"))
        assertTrue(ExplicacaoBateria.ehXiaomi("  poco "))
    }

    @Test
    fun `outros fabricantes, nome vazio e nulo nao sao xiaomi`() {
        assertFalse(ExplicacaoBateria.ehXiaomi("Samsung"))
        assertFalse(ExplicacaoBateria.ehXiaomi("Google"))
        assertFalse(ExplicacaoBateria.ehXiaomi(""))
        assertFalse(ExplicacaoBateria.ehXiaomi(null))
    }

    @Test
    fun `com restricao ainda ativa a tela e a pergunta direta do android`() {
        assertEquals(ExplicacaoBateria.Tela.PERGUNTA, ExplicacaoBateria.telaDoPedido(otimizacaoIgnorada = false))
    }

    @Test
    fun `ja sem restricao a tela e a lista geral de otimizacao`() {
        assertEquals(ExplicacaoBateria.Tela.LISTA, ExplicacaoBateria.telaDoPedido(otimizacaoIgnorada = true))
    }
}
