package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtUseCaseTest {

    @Test
    fun `imagem menor que o limite nao e reduzida`() {
        assertEquals(1, calcularAmostra(300, 400, 400))
    }

    @Test
    fun `capa grande e reduzida pela potencia de dois que mantem o lado maior acima do limite`() {
        // 1600 / 2 = 800 >= 400 (divide); 1600 / 4 = 400 >= 400 (divide); 1600 / 8 = 200 < 400 (para)
        assertEquals(4, calcularAmostra(1200, 1600, 400))
    }

    @Test
    fun `usa o lado maior mesmo quando a largura e a menor`() {
        assertEquals(4, calcularAmostra(1000, 2000, 400))
    }

    @Test
    fun `dimensoes desconhecidas resultam em amostra 1`() {
        assertEquals(1, calcularAmostra(-1, -1, 400))
    }

    @Test
    fun `limite zero ou negativo nao reduz a imagem`() {
        assertEquals(1, calcularAmostra(1600, 1600, 0))
    }
}
