package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CompartilharTrechoTest {

    private val paragrafos = listOf(
        Paragrafo("primeiro", 0, 0),
        Paragrafo("segundo", 8, 0),
        Paragrafo("terceiro", 16, 0),
    )

    @Test
    fun guiadaVenceATela() {
        val escolhido = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = 2,
            rolouParaLonge = false,
            indiceNaTela = { 0 },
        )
        assertEquals("terceiro", escolhido?.texto)
    }

    @Test
    fun semGuiadaUsaATela() {
        val escolhido = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = -1,
            rolouParaLonge = false,
            indiceNaTela = { 1 },
        )
        assertEquals("segundo", escolhido?.texto)
    }

    @Test
    fun rolouParaLongeUsaATela() {
        val escolhido = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = 2,
            rolouParaLonge = true,
            indiceNaTela = { 0 },
        )
        assertEquals("primeiro", escolhido?.texto)
    }

    @Test
    fun rolouSemTelaCaiNaGuiada() {
        val escolhido = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = 1,
            rolouParaLonge = true,
            indiceNaTela = { null },
        )
        assertEquals("segundo", escolhido?.texto)
    }

    @Test
    fun semGuiadaNemTelaDaNulo() {
        val escolhido = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = null,
            rolouParaLonge = false,
            indiceNaTela = { null },
        )
        assertNull(escolhido)
    }

    @Test
    fun indiceForaDosLimitesDaNulo() {
        val foraDaGuiada = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = 3,
            rolouParaLonge = false,
            indiceNaTela = { 0 },
        )
        val foraDaTela = paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = null,
            rolouParaLonge = false,
            indiceNaTela = { 7 },
        )
        assertNull(foraDaGuiada)
        assertNull(foraDaTela)
    }

    @Test
    fun paragrafoEmBrancoDaNulo() {
        val comBranco = listOf(Paragrafo("  \n\t ", 0, 0))
        val escolhido = paragrafoParaCompartilhar(
            comBranco,
            indiceGuiada = 0,
            rolouParaLonge = false,
            indiceNaTela = { null },
        )
        assertNull(escolhido)
    }

    @Test
    fun semTextoDaNulo() {
        val escolhido = paragrafoParaCompartilhar(
            null,
            indiceGuiada = 0,
            rolouParaLonge = false,
            indiceNaTela = { 0 },
        )
        assertNull(escolhido)
    }

    @Test
    fun naTelaNaoEChamadoComGuiada() {
        var consultouTela = false
        paragrafoParaCompartilhar(
            paragrafos,
            indiceGuiada = 0,
            rolouParaLonge = false,
            indiceNaTela = {
                consultouTela = true
                0
            },
        )
        assertFalse("Com leitura guiada, o índice da tela não deveria ser consultado", consultouTela)
    }
}
