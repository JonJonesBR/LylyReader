package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.CapituloAudio
import com.jonjonesbr.audiobookgen.util.capituloAnteriorMs
import com.jonjonesbr.audiobookgen.util.proximoCapituloMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNavigationTest {

    private val capitulos = listOf(
        CapituloAudio("Cap 1", inicioMs = 0L, fimMs = 10_000L),
        CapituloAudio("Cap 2", inicioMs = 10_000L, fimMs = 25_000L),
        CapituloAudio("Cap 3", inicioMs = 25_000L, fimMs = 40_000L),
    )

    @Test fun listaVaziaNaoTemProximoNemAnterior() {
        assertNull(proximoCapituloMs(emptyList(), 5_000L))
        assertNull(capituloAnteriorMs(emptyList(), 5_000L))
    }

    @Test fun proximoCapituloPulaParaOInicioDoSeguinte() {
        assertEquals(10_000L, proximoCapituloMs(capitulos, 3_000L))
        assertEquals(25_000L, proximoCapituloMs(capitulos, 10_000L))
    }

    @Test fun proximoCapituloNoUltimoRetornaNull() {
        assertNull(proximoCapituloMs(capitulos, 30_000L))
    }

    @Test fun anteriorDentroDaMargemVoltaUmCapitulo() {
        // 1s dentro do Cap 2 (início 10_000): dentro da margem de 3s, volta pro Cap 1.
        assertEquals(0L, capituloAnteriorMs(capitulos, 11_000L))
    }

    @Test fun anteriorAlemDaMargemReiniciaOCapituloAtual() {
        // 5s dentro do Cap 2: além da margem de 3s, reinicia o Cap 2 em vez de voltar pro Cap 1.
        assertEquals(10_000L, capituloAnteriorMs(capitulos, 15_000L))
    }

    @Test fun anteriorNoPrimeiroCapituloAlemDaMargemReiniciaEle() {
        assertEquals(0L, capituloAnteriorMs(capitulos, 5_000L))
    }

    @Test fun anteriorNoPrimeiroCapituloDentroDaMargemRetornaNull() {
        assertNull(capituloAnteriorMs(capitulos, 1_000L))
    }

    @Test fun posicaoAntesDoPrimeiroCapituloRetornaNullParaAnterior() {
        assertNull(capituloAnteriorMs(capitulos, -1L))
    }
}
