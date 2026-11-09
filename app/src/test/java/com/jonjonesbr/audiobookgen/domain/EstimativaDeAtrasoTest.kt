package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EstimativaDeAtrasoTest {

    private fun estimativaCom(vararg amostras: Long): EstimativaDeAtraso =
        EstimativaDeAtraso().also { e -> amostras.forEach { e.add(it) } }

    @Test fun `com menos de tres amostras a estimativa e zero`() {
        assertEquals(0L, EstimativaDeAtraso().estimativaMs())
        assertEquals(0L, estimativaCom(300L).estimativaMs())
        assertEquals(0L, estimativaCom(300L, 368L).estimativaMs())
    }

    @Test fun `com tres amostras a estimativa e a mediana`() {
        assertEquals(368L, estimativaCom(300L, 368L, 400L).estimativaMs())
        assertEquals(368L, estimativaCom(400L, 300L, 368L).estimativaMs())
    }

    @Test fun `com numero par de amostras a mediana e a media dos dois do meio`() {
        assertEquals(450L, estimativaCom(300L, 400L, 500L, 600L).estimativaMs())
    }

    @Test fun `a janela guarda so as oito ultimas amostras`() {
        val e = estimativaCom(*LongArray(8) { 100L })
        assertEquals(8, e.quantidade)
        repeat(8) { e.add(300L) }
        assertEquals(8, e.quantidade)
        assertEquals(300L, e.estimativaMs())
    }

    @Test fun `amostras negativas ou acima do limite nao entram`() {
        val e = estimativaCom(-5L, 2000L, 300L, 300L)
        assertEquals(2, e.quantidade)
        assertEquals(0L, e.estimativaMs())
        e.add(1500L)
        assertEquals(3, e.quantidade)
        assertEquals(300L, e.estimativaMs())
    }

    @Test fun `a estimativa e limitada a seiscentos ms`() {
        assertEquals(600L, estimativaCom(900L, 900L, 900L).estimativaMs())
    }

    @Test fun `zerar volta a estimativa a zero`() {
        val e = estimativaCom(368L, 368L, 368L)
        e.zerar()
        assertEquals(0, e.quantidade)
        assertEquals(0L, e.estimativaMs())
    }
}
