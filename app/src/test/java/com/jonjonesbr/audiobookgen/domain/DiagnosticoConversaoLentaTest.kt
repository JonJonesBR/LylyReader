package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticoConversaoLentaTest {

    @Test
    fun `mensagem com lenta igual a 1 indica conexao lenta`() {
        assertTrue(
            DiagnosticoConversaoLenta.mensagemIndicaConexaoLenta(
                "@progresso pct=11 eta_s=3480 lenta=1"
            )
        )
    }

    @Test
    fun `mensagem multilinha com capitulo e lenta igual a 1 indica conexao lenta`() {
        val msg = "Capítulo 3 de 3\n@progresso pct=11 eta_s=3480 lenta=1"
        assertTrue(DiagnosticoConversaoLenta.mensagemIndicaConexaoLenta(msg))
    }

    @Test
    fun `mensagem normal ou texto livre nao indica conexao lenta`() {
        assertFalse(DiagnosticoConversaoLenta.mensagemIndicaConexaoLenta("@progresso pct=11 eta_s=120"))
        assertFalse(DiagnosticoConversaoLenta.mensagemIndicaConexaoLenta("Preparando a voz…"))
    }

    @Test
    fun `montarCamposExtras inclui ipv6 e prefixa metricas python descartando vazios`() {
        val extras = DiagnosticoConversaoLenta.montarCamposExtras(
            motor = "edge",
            voz = "pt-BR-FranciscaNeural",
            pct = 11,
            ipv6Ativo = true,
            resumoPython = mapOf(
                "timeouts" to "3",
                "ultimo_erro" to " ",
                "edge_fallback_ipv4_ativo" to "True",
            ),
        )

        assertEquals("edge", extras["motor"])
        assertEquals("pt-BR-FranciscaNeural", extras["voz"])
        assertEquals("11", extras["progresso_pct"])
        assertEquals("sim", extras["ipv6_presente"])
        assertEquals("3", extras["edge_timeouts"])
        assertEquals("True", extras["edge_fallback_ipv4_ativo"])
        assertFalse(extras.containsKey("edge_ultimo_erro"))
    }

    @Test
    fun `montarCamposExtras trata ipv6 falso e desconhecido`() {
        val semIpv6 = DiagnosticoConversaoLenta.montarCamposExtras("edge", "voz", 20, false, emptyMap())
        val desconhecido = DiagnosticoConversaoLenta.montarCamposExtras("edge", "voz", 20, null, emptyMap())

        assertEquals("nao", semIpv6["ipv6_presente"])
        assertEquals("desconhecido", desconhecido["ipv6_presente"])
    }
}
