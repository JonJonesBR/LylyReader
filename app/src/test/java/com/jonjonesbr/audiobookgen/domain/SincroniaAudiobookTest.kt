package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SincroniaAudiobookTest {

    /** Dois capítulos: o 1º com 2 parágrafos curtos, o 2º com 4 parágrafos de 100 caracteres. */
    private fun texto(): TextoExtraido {
        val textos = listOf("a".repeat(10), "b".repeat(10), "c".repeat(100), "d".repeat(100), "e".repeat(100), "f".repeat(100))
        var offset = 0
        val paragrafos = textos.mapIndexed { i, t ->
            Paragrafo(t, offset, if (i < 2) 0 else 1).also { offset += t.length + 2 }
        }
        return TextoExtraido(
            capitulos = listOf(Capitulo("Um", 0, 1), Capitulo("Dois", 2, 5)),
            paragrafos = paragrafos,
            totalChars = offset,
        )
    }

    @Test
    fun usaAMarcaDoCapituloEAProporcaoSoDentroDele() {
        // O capítulo 1 é curto no texto mas longo no áudio (ex.: abertura lenta): sem as marcas a proporção erraria.
        val audio = listOf(CapituloAudio("Um", 0, 60_000), CapituloAudio("Dois", 60_000, 100_000))
        assertEquals(0, paragrafoNoAudiobook(10_000, 100_000, audio, texto()))
        assertEquals(2, paragrafoNoAudiobook(61_000, 100_000, audio, texto()))
        assertEquals(4, paragrafoNoAudiobook(82_000, 100_000, audio, texto()))
        assertEquals(5, paragrafoNoAudiobook(99_999, 100_000, audio, texto()))
    }

    @Test
    fun semMarcasCompativeisUsaAProporcaoDoLivro() {
        val t = texto()
        assertEquals(0, paragrafoNoAudiobook(0, 100_000, emptyList(), t))
        assertEquals(5, paragrafoNoAudiobook(100_000, 100_000, emptyList(), t))
        // Quantidade de capítulos diferente: ignora as marcas.
        assertEquals(0, paragrafoNoAudiobook(0, 100_000, listOf(CapituloAudio("x", 0, 1), CapituloAudio("y", 1, 2), CapituloAudio("z", 2, 3)), t))
    }

    @Test
    fun semTextoOuSemDuracaoDevolveMenosUm() {
        assertEquals(-1, paragrafoNoAudiobook(0, 0, emptyList(), texto()))
        assertEquals(-1, paragrafoNoAudiobook(0, 100, emptyList(), TextoExtraido(emptyList(), emptyList(), 0)))
    }
}
