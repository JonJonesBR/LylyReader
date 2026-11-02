package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.data.StatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResumoEstatisticasTest {

    /** Frases de faz-de-conta: deixam visível qual contagem caiu em cada trecho. */
    private val frases = object : ResumoEstatisticas.Frases {
        override fun cabecalho() = "Minhas leituras:"
        override fun minutosOuvidos(quantidade: Int) = "$quantidade ouvidos"
        override fun minutosLidos(quantidade: Int) = "$quantidade lidos"
        override fun livrosConcluidos(quantidade: Int) = "$quantidade livros"
        override fun diasSeguidos(quantidade: Int) = "$quantidade dias"
        override fun ultimos7Dias(minutosPorDia: String) = "Ultimos 7 dias: $minutosPorDia"
    }

    private fun dia(data: String, ouvidos: Int, lidos: Int) = StatsStore.DiaStats(data, ouvidos, lidos)

    @Test
    fun resumoTragOsQuatroTotaisNaOrdem() {
        val totais = StatsStore.Totais(totalMinutosOuvidos = 30, totalMinutosLidos = 36, livrosConcluidos = 2, streakDias = 3)

        val texto = ResumoEstatisticas.montarTexto(totais, emptyList(), frases)

        assertEquals("Minhas leituras: 30 ouvidos, 36 lidos, 2 livros, 3 dias.", texto)
    }

    @Test
    fun segundaLinhaSomaOuvidoELidoDeCadaDia() {
        val totais = StatsStore.Totais(totalMinutosOuvidos = 15, totalMinutosLidos = 2, livrosConcluidos = 0, streakDias = 2)
        val dias = listOf(dia("2026-10-27", 0, 0), dia("2026-10-28", 10, 5), dia("2026-10-29", 0, 2))

        val texto = ResumoEstatisticas.montarTexto(totais, dias, frases)

        assertEquals("Minhas leituras: 15 ouvidos, 2 lidos, 0 livros, 2 dias.\nUltimos 7 dias: 0, 15, 2", texto)
    }

    @Test
    fun semMinutosNaJanelaNaoHaSegundaLinha() {
        val totais = StatsStore.Totais(totalMinutosOuvidos = 0, totalMinutosLidos = 0, livrosConcluidos = 0, streakDias = 0)
        val dias = listOf(dia("2026-10-28", 0, 0), dia("2026-10-29", 0, 0))

        val texto = ResumoEstatisticas.montarTexto(totais, dias, frases)

        assertFalse(texto.contains("\n"))
    }
}
