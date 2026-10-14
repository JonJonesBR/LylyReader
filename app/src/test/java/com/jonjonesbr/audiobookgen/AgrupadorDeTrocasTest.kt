package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.AgrupadorDeTrocas
import com.jonjonesbr.audiobookgen.domain.TrocaAgrupada
import com.jonjonesbr.audiobookgen.domain.TrocaNormalizador
import com.jonjonesbr.audiobookgen.domain.formatarLinhaTroca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgrupadorDeTrocasTest {

    private fun troca(antes: String, depois: String) = TrocaNormalizador(antes, depois)

    @Test
    fun listaVaziaDevolveListaVazia() {
        assertEquals(emptyList<TrocaAgrupada>(), AgrupadorDeTrocas.agrupar(emptyList(), 10))
    }

    @Test
    fun paresIguaisSaoContadosEmUmaLinha() {
        val trocas = listOf(
            troca("Dr.", "doutor"),
            troca("10h30", "dez horas e trinta minutos"),
            troca("Dr.", "doutor"),
            troca("Dr.", "doutor"),
        )
        assertEquals(
            listOf(
                TrocaAgrupada("Dr.", "doutor", 3),
                TrocaAgrupada("10h30", "dez horas e trinta minutos", 1),
            ),
            AgrupadorDeTrocas.agrupar(trocas, 10),
        )
    }

    @Test
    fun mesmoAntesComDepoisDiferentesNaoSeFunde() {
        val trocas = listOf(troca("2º", "segundo"), troca("2º", "segunda"))
        val agrupadas = AgrupadorDeTrocas.agrupar(trocas, 10)
        assertEquals(2, agrupadas.size)
        assertTrue(agrupadas.all { it.contagem == 1 })
    }

    @Test
    fun ordenaPorContagemDescendenteDepoisAlfabetica() {
        val trocas = listOf(
            troca("zeta", "z"),
            troca("beta", "b"),
            troca("alfa", "a"),
            troca("alfa", "a"),
            troca("beta", "b"),
            troca("gama", "g"),
        )
        assertEquals(
            listOf("alfa", "beta", "gama", "zeta"),
            AgrupadorDeTrocas.agrupar(trocas, 10).map { it.antes },
        )
        assertEquals(2, AgrupadorDeTrocas.agrupar(trocas, 10)[0].contagem)
    }

    @Test
    fun empateDeContagemOrdenaPeloAntesSemDiferenciarMaiusculas() {
        val trocas = listOf(troca("Bravo", "b"), troca("alfa", "a"))
        assertEquals(
            listOf("alfa", "Bravo"),
            AgrupadorDeTrocas.agrupar(trocas, 10).map { it.antes },
        )
    }

    @Test
    fun cortaNoLimite() {
        val trocas = listOf(troca("a", "A"), troca("b", "B"), troca("c", "C"))
        assertEquals(2, AgrupadorDeTrocas.agrupar(trocas, 2).size)
    }

    @Test
    fun linhaMostraAntesDepoisEContagem() {
        assertEquals(
            "Dr. → doutor (×3)",
            formatarLinhaTroca(TrocaAgrupada("Dr.", "doutor", 3)),
        )
    }

    @Test
    fun linhaComTextoVazioAindaTemSetaEContagem() {
        assertEquals(" →  (×1)", formatarLinhaTroca(TrocaAgrupada("", "", 1)))
    }

    @Test
    fun limiteZeroOuNegativoDevolveVazio() {
        val trocas = listOf(troca("a", "A"))
        assertEquals(emptyList<TrocaAgrupada>(), AgrupadorDeTrocas.agrupar(trocas, 0))
        assertEquals(emptyList<TrocaAgrupada>(), AgrupadorDeTrocas.agrupar(trocas, -1))
    }
}
