package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioAacTranscoderTest {

    @Test
    fun `pts do pedaco sem deslocamento e o pts do bloco`() {
        assertEquals(5_000L, ptsDoPedaco(5_000L, 0, 44_100, 2))
    }

    @Test
    fun `um quadro mono de 16 bits a 1000 Hz soma 1 ms ao pts do bloco`() {
        // 1 canal x 2 bytes = 2 bytes por quadro; 2 bytes a 1000 Hz = 1000 us
        assertEquals(6_000L, ptsDoPedaco(5_000L, 2, 1_000, 1))
    }

    @Test
    fun `um segundo de pcm mono a 16 kHz avanca exatamente um segundo`() {
        // 16000 amostras x 2 bytes = 32000 bytes
        assertEquals(1_000_000L, ptsDoPedaco(0L, 32_000, 16_000, 1))
    }

    @Test
    fun `estereo considera 4 bytes por quadro`() {
        // 2 canais x 2 bytes = 4 bytes por quadro; 4 bytes a 1000 Hz = 1000 us
        assertEquals(1_000L, ptsDoPedaco(0L, 4, 1_000, 2))
    }

    @Test
    fun `deslocamento que nao fecha microssegundo inteiro e truncado`() {
        // 4 bytes estereo a 44,1 kHz = 22,67 us, truncado para 22
        assertEquals(22L, ptsDoPedaco(0L, 4, 44_100, 2))
    }

    @Test
    fun `pts de uma hora de audio nao estoura inteiro de 32 bits`() {
        // 1 h mono a 44,1 kHz com 16 bits = 317.520.000 bytes
        assertEquals(3_600_000_000L, ptsDoPedaco(0L, 317_520_000, 44_100, 1))
    }

    @Test
    fun `tamanho do pedaco e o restante quando ele cabe na capacidade e e alinhado`() {
        assertEquals(8, tamanhoAlinhado(100, 8, 4))
    }

    @Test
    fun `tamanho do pedaco e limitado pela capacidade quando ela e alinhada`() {
        assertEquals(8, tamanhoAlinhado(8, 100, 4))
    }

    @Test
    fun `tamanho do pedaco arredonda para baixo ao quadro quando a capacidade nao e alinhada`() {
        assertEquals(8, tamanhoAlinhado(10, 100, 4))
    }

    @Test
    fun `tamanho do pedaco arredonda para baixo ao quadro quando o restante nao e alinhado`() {
        assertEquals(8, tamanhoAlinhado(100, 11, 4))
    }

    @Test
    fun `capacidade igual a um quadro e mantida`() {
        assertEquals(4, tamanhoAlinhado(4, 100, 4))
    }

    @Test
    fun `capacidade menor que um quadro devolve a propria capacidade sem alinhar`() {
        // Ramo defensivo, não ocorre com codecs reais (ver KDoc).
        assertEquals(3, tamanhoAlinhado(3, 100, 4))
    }

    @Test
    fun `restante menor que um quadro devolve o restante sem alinhar`() {
        // Último pedaço do bloco, quando sobra menos que um quadro: entregue como está.
        assertEquals(3, tamanhoAlinhado(100, 3, 4))
    }

    @Test
    fun `sem restante o tamanho do pedaco e zero`() {
        assertEquals(0, tamanhoAlinhado(100, 0, 4))
    }
}
