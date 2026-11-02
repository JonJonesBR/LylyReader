package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressoDaConversaoTest {

    @Test
    fun `le a linha estruturada com pct, eta e falhas`() {
        assertEquals(
            ProgressoEstruturado(pct = 18, etaSegundos = 168, falhas = 2),
            ProgressoDaConversao.ler("@progresso pct=18 eta_s=168 falhas=2")
        )
    }

    @Test
    fun `eta negativo significa que ainda nao ha estimativa e falhas ausentes valem zero`() {
        assertEquals(
            ProgressoEstruturado(pct = 5, etaSegundos = null, falhas = 0),
            ProgressoDaConversao.ler("@progresso pct=5 eta_s=-1")
        )
    }

    @Test
    fun `linha sem o prefixo ou sem percentual nao e progresso`() {
        assertNull(ProgressoDaConversao.ler("Preparando a voz…"))
        assertNull(ProgressoDaConversao.ler("Capítulo 3 de 10"))
        assertNull(ProgressoDaConversao.ler("@progresso eta_s=10"))
        assertNull(ProgressoDaConversao.ler("@progresso pct=abc"))
    }

    @Test
    fun `partes separa o capitulo da linha estruturada e deixa o texto como esta`() {
        val partes = ProgressoDaConversao.partes("Capítulo 3 de 10\n@progresso pct=40 eta_s=90")
        assertEquals(
            listOf(
                ParteDaMensagem.Texto("Capítulo 3 de 10"),
                ParteDaMensagem.Progresso(ProgressoEstruturado(pct = 40, etaSegundos = 90, falhas = 0)),
            ),
            partes
        )
    }

    @Test
    fun `tempo restante em faixas sem segundos`() {
        assertEquals(TempoRestante.Calculando, ProgressoDaConversao.tempoRestante(null))
        assertEquals(TempoRestante.MenosDeUmMinuto, ProgressoDaConversao.tempoRestante(0))
        assertEquals(TempoRestante.MenosDeUmMinuto, ProgressoDaConversao.tempoRestante(59))
        assertEquals(TempoRestante.Minutos(1), ProgressoDaConversao.tempoRestante(60))
        // 168 s = 2 min 48 s: a frase diz "cerca de 2 min".
        assertEquals(TempoRestante.Minutos(2), ProgressoDaConversao.tempoRestante(168))
        assertEquals(TempoRestante.Minutos(59), ProgressoDaConversao.tempoRestante(3599))
        assertEquals(TempoRestante.HorasEMinutos(1, 0), ProgressoDaConversao.tempoRestante(3600))
        assertEquals(TempoRestante.HorasEMinutos(2, 5), ProgressoDaConversao.tempoRestante(7500))
    }

    @Test
    fun `percentual fora de 0 a 100 e limitado`() {
        assertEquals(100, ProgressoDaConversao.ler("@progresso pct=150 eta_s=1")?.pct)
        assertTrue(ProgressoDaConversao.ler("@progresso pct=-3")?.pct == 0)
    }
}
