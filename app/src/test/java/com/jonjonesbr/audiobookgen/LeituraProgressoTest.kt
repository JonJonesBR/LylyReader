package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.util.minutosRestantesCapitulo
import com.jonjonesbr.audiobookgen.util.progressoLivroPct
import org.junit.Assert.assertEquals
import org.junit.Test

class LeituraProgressoTest {

    private fun paragrafo(texto: String, offset: Int) =
        Paragrafo(texto = texto, charOffset = offset, indiceCapitulo = 0)

    @Test fun progressoZeroNoInicio() {
        val paragrafos = listOf(paragrafo("abc", 0), paragrafo("def", 50))
        assertEquals(0f, progressoLivroPct(paragrafos, 0, 100), 0.001f)
    }

    @Test fun progressoMeioDoLivro() {
        val paragrafos = listOf(paragrafo("abc", 0), paragrafo("def", 50))
        assertEquals(0.5f, progressoLivroPct(paragrafos, 1, 100), 0.001f)
    }

    @Test fun progressoTotalCharsZeroRetornaZero() {
        val paragrafos = listOf(paragrafo("abc", 0))
        assertEquals(0f, progressoLivroPct(paragrafos, 0, 0), 0.001f)
    }

    @Test fun progressoIndiceForaDoIntervaloRetornaZero() {
        val paragrafos = listOf(paragrafo("abc", 0))
        assertEquals(0f, progressoLivroPct(paragrafos, 5, 100), 0.001f)
    }

    @Test fun progressoNuncaUltrapassa100Porcento() {
        val paragrafos = listOf(paragrafo("abc", 150))
        assertEquals(1f, progressoLivroPct(paragrafos, 0, 100), 0.001f)
    }

    @Test fun minutosRestantesContaSoOProximoParagrafoEmDiante() {
        // 200 palavras/min padrão; parágrafo atual (0) não conta, só os seguintes (1, 2).
        val paragrafos = listOf(
            paragrafo("uma duas tres quatro cinco", 0),   // indice 0, atual — não conta
            paragrafo(List(200) { "palavra" }.joinToString(" "), 30), // indice 1: 200 palavras = 1 min
            paragrafo(List(100) { "palavra" }.joinToString(" "), 500) // indice 2: 100 palavras
        )
        val capitulos = listOf(Capitulo("Cap 1", indiceParagrafoInicio = 0, indiceParagrafoFim = 2))
        // 200 + 100 = 300 palavras restantes / 200 wpm = 1.5 -> ceil = 2
        assertEquals(2, minutosRestantesCapitulo(paragrafos, 0, capitulos))
    }

    @Test fun minutosRestantesZeroNoUltimoParagrafoDoCapitulo() {
        val paragrafos = listOf(paragrafo("uma duas", 0), paragrafo("tres quatro", 10))
        val capitulos = listOf(Capitulo("Cap 1", indiceParagrafoInicio = 0, indiceParagrafoFim = 1))
        assertEquals(0, minutosRestantesCapitulo(paragrafos, 1, capitulos))
    }

    @Test fun minutosRestantesIndiceForaDeQualquerCapituloRetornaZero() {
        val paragrafos = listOf(paragrafo("uma duas", 0))
        val capitulos = listOf(Capitulo("Cap 1", indiceParagrafoInicio = 5, indiceParagrafoFim = 10))
        assertEquals(0, minutosRestantesCapitulo(paragrafos, 0, capitulos))
    }

    @Test fun minutosRestantesRespeitaVelocidadePersonalizada() {
        val paragrafos = listOf(
            paragrafo("atual", 0),
            paragrafo(List(50) { "palavra" }.joinToString(" "), 10)
        )
        val capitulos = listOf(Capitulo("Cap 1", indiceParagrafoInicio = 0, indiceParagrafoFim = 1))
        // 50 palavras / 50 wpm = 1 min
        assertEquals(1, minutosRestantesCapitulo(paragrafos, 0, capitulos, palavrasPorMinuto = 50))
    }
}
