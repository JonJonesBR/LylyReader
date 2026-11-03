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
        val escolhido = paragrafoParaCompartilhar(paragrafos, indiceGuiada = 2, indiceNaTela = { 0 })
        assertEquals("terceiro", escolhido?.texto)
    }

    @Test
    fun semGuiadaUsaATela() {
        val escolhido = paragrafoParaCompartilhar(paragrafos, indiceGuiada = -1, indiceNaTela = { 1 })
        assertEquals("segundo", escolhido?.texto)
    }

    @Test
    fun semGuiadaNemTelaDaNulo() {
        assertNull(paragrafoParaCompartilhar(paragrafos, indiceGuiada = null, indiceNaTela = { null }))
    }

    @Test
    fun indiceForaDosLimitesDaNulo() {
        assertNull(paragrafoParaCompartilhar(paragrafos, indiceGuiada = 3, indiceNaTela = { 0 }))
        assertNull(paragrafoParaCompartilhar(paragrafos, indiceGuiada = null, indiceNaTela = { 7 }))
    }

    @Test
    fun paragrafoEmBrancoDaNulo() {
        val comBranco = listOf(Paragrafo("  \n\t ", 0, 0))
        assertNull(paragrafoParaCompartilhar(comBranco, indiceGuiada = 0, indiceNaTela = { null }))
    }

    @Test
    fun semTextoDaNulo() {
        assertNull(paragrafoParaCompartilhar(null, indiceGuiada = 0, indiceNaTela = { 0 }))
    }

    @Test
    fun naTelaNaoEChamadoComGuiada() {
        var consultouTela = false
        paragrafoParaCompartilhar(paragrafos, indiceGuiada = 0, indiceNaTela = {
            consultouTela = true
            0
        })
        assertFalse("Com leitura guiada, o índice da tela não deveria ser consultado", consultouTela)
    }
}
