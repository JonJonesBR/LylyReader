package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PausasNaturaisTest {

    private fun amostras(n: Int = 200, f: (Random) -> Int): List<Int> {
        val aleatorio = Random(42)
        return List(n) { f(aleatorio) }
    }

    @Test fun `pausa depois de ponto fica na faixa e varia`() {
        val pausas = amostras { PausasNaturais.aposTrecho("Ele saiu de casa.", aleatorio = it) }
        assertTrue(pausas.all { it in 380..650 })
        assertTrue("as pausas devem variar", pausas.toSet().size > 20)
    }

    @Test fun `reticencias pausam mais que ponto e ponto mais que virgula`() {
        val reticencias = amostras { PausasNaturais.aposTrecho("E então…", aleatorio = it) }.average()
        val ponto = amostras { PausasNaturais.aposTrecho("E então.", aleatorio = it) }.average()
        val virgula = amostras { PausasNaturais.aposTrecho("E então,", aleatorio = it) }.average()
        assertTrue(reticencias > ponto)
        assertTrue(ponto > virgula)
    }

    @Test fun `pontuacao antes de aspas e travessao conta`() {
        val pausas = amostras { PausasNaturais.aposTrecho("— Você vem? — ", aleatorio = it) }
        assertTrue(pausas.all { it in 450..750 })
    }

    @Test fun `frase longa ganha folego`() {
        val longa = "palavra ".repeat(25).trim() + "."
        val pausas = amostras { PausasNaturais.aposTrecho(longa, aleatorio = it) }
        assertTrue(pausas.all { it in 460..870 })
        assertTrue(pausas.average() > amostras { PausasNaturais.aposTrecho("Curta.", aleatorio = it) }.average())
    }

    @Test fun `fim de paragrafo pausa mais e paragrafo curto menos`() {
        val longo = "Ela atravessou a praça devagar, olhando as vitrines apagadas e os bancos vazios."
        val paragrafo = amostras { PausasNaturais.aposParagrafo(longo, aleatorio = it) }
        val curto = amostras { PausasNaturais.aposParagrafo("— Sim.", aleatorio = it) }
        assertTrue(paragrafo.all { it in 800..1300 })
        assertTrue(curto.all { it in 500..850 })
    }

    @Test fun `leitura acelerada encurta a pausa`() {
        val normal = PausasNaturais.aposTrecho("Fim.", velocidade = 1f, aleatorio = Random(7))
        val rapida = PausasNaturais.aposTrecho("Fim.", velocidade = 2f, aleatorio = Random(7))
        assertEquals(normal / 2, rapida)
    }
}
