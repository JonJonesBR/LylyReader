package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderVozControllerTest {

    private fun paragrafos(quantos: Int): List<Paragrafo> =
        List(quantos) { i -> Paragrafo(texto = "p$i", charOffset = i, indiceCapitulo = 0) }

    private fun comTextos(vararg textos: String): List<Paragrafo> =
        textos.mapIndexed { i, t -> Paragrafo(texto = t, charOffset = i, indiceCapitulo = 0) }

    // ── faixaDoPreparo ────────────────────────────────────────────────────────

    @Test
    fun semCapituloPreparaCentoEVinte() {
        assertEquals(10 to 130, faixaDoPreparo(paragrafos(500), emptyList(), 10))
    }

    @Test
    fun fimLimitadoAoUltimo() {
        assertEquals(0 to 49, faixaDoPreparo(paragrafos(50), emptyList(), 0))
    }

    @Test
    fun inicioForaDaListaVaiParaOsLimites() {
        assertEquals(4 to 4, faixaDoPreparo(paragrafos(5), emptyList(), 99))
        assertEquals(0 to 4, faixaDoPreparo(paragrafos(5), emptyList(), -3))
    }

    @Test
    fun capituloDefineOFim() {
        val capitulos = listOf(Capitulo("A", 0, 3), Capitulo("B", 4, 9))
        val lista = List(10) { i -> Paragrafo(texto = "p$i", charOffset = i, indiceCapitulo = if (i < 4) 0 else 1) }
        assertEquals(5 to 9, faixaDoPreparo(lista, capitulos, 5))
        assertEquals(0 to 3, faixaDoPreparo(lista, capitulos, 0))
    }

    // ── textoDaAmostra ────────────────────────────────────────────────────────

    @Test
    fun amostraJuntaTresParagrafos() {
        assertEquals("a b c", textoDaAmostra(comTextos("a", "b", "c", "d", "e")))
    }

    @Test
    fun amostraVaziaDaNulo() {
        assertNull(textoDaAmostra(null))
        assertNull(textoDaAmostra(emptyList()))
        assertNull(textoDaAmostra(comTextos("  ", " ")))
    }
}
