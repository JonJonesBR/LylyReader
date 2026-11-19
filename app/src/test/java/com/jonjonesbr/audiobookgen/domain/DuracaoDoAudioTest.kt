package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DuracaoDoAudioTest {

    @Test
    fun `150 palavras no ritmo normal dao 1 minuto`() {
        assertEquals(1.minutes, estimarDuracaoDoAudio(150, 100))
    }

    @Test
    fun `ritmo mais rapido encurta o audio`() {
        // 200% dobra a velocidade: 150 palavras viram 30 segundos.
        assertEquals(30.seconds, estimarDuracaoDoAudio(150, 200))
        assertTrue(estimarDuracaoDoAudio(150, 150) < estimarDuracaoDoAudio(150, 100))
    }

    @Test
    fun `ritmo mais lento alonga o audio`() {
        assertEquals(2.minutes, estimarDuracaoDoAudio(150, 50))
    }

    @Test
    fun `zero palavras dao zero`() {
        assertEquals(Duration.ZERO, estimarDuracaoDoAudio(0, 100))
        assertEquals(Duration.ZERO, estimarDuracaoDoAudio(-5, 100))
    }

    @Test
    fun `ritmo zero nao divide por zero`() {
        assertTrue(estimarDuracaoDoAudio(10, 0).isPositive())
    }

    @Test
    fun `contar palavras ignora espacos repetidos e quebras de linha`() {
        assertEquals(5, contarPalavras(listOf("Um dois  três", "\n quatro\tcinco ")))
    }

    @Test
    fun `contar palavras de texto vazio ou so espacos da zero`() {
        assertEquals(0, contarPalavras(listOf("", "   ", "\n")))
        assertEquals(0, contarPalavras(emptyList()))
    }
}
