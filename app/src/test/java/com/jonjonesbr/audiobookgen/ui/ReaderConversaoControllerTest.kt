package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderConversaoControllerTest {

    private val caminho = "/livros/dom.txt"

    private fun textoDeQuatro(): TextoExtraido = TextoExtraido(
        capitulos = listOf(Capitulo("Um", 0, 1), Capitulo("Dois", 2, 3)),
        paragrafos = listOf("a", "b", "c", "d").mapIndexed { i, t ->
            Paragrafo(texto = t, charOffset = i, indiceCapitulo = if (i < 2) 0 else 1)
        },
        totalChars = 4,
    )

    // ── geraDoTextoDoLeitor ──────────────────────────────────────────────────

    @Test
    fun edicaoGeraDireto() {
        assertTrue(geraDoTextoDoLeitor(temEdicoes = true, livroNaBiblioteca = true))
        assertTrue(geraDoTextoDoLeitor(temEdicoes = true, livroNaBiblioteca = false))
    }

    @Test
    fun livroDaBibliotecaSemEdicaoAbreOPainel() {
        assertFalse(geraDoTextoDoLeitor(temEdicoes = false, livroNaBiblioteca = true))
    }

    @Test
    fun arquivoForaDaBibliotecaGeraDireto() {
        assertTrue(geraDoTextoDoLeitor(temEdicoes = false, livroNaBiblioteca = false))
    }

    // ── motorProntoOffline ────────────────────────────────────────────────────

    @Test
    fun motorAndroidSoEstaProntoComVozValida() {
        assertTrue(motorProntoOffline("android", temVozAndroidValida = true) { false })
        assertFalse(motorProntoOffline("android", temVozAndroidValida = false) { true })
    }

    @Test
    fun supertonicSempreEstaProntoEFicaSemLerKokoro() {
        var lido = false
        assertTrue(motorProntoOffline("onnx", temVozAndroidValida = false) { lido = true; false })
        assertFalse(lido)
    }

    @Test
    fun kokoroDependeDoModeloBaixado() {
        assertTrue(motorProntoOffline("kokoro", temVozAndroidValida = false) { true })
        assertFalse(motorProntoOffline("kokoro", temVozAndroidValida = true) { false })
    }

    @Test
    fun motorDesconhecidoOuEdgeNaoEstaPronto() {
        var lido = false
        assertFalse(motorProntoOffline("edge", temVozAndroidValida = true) { lido = true; true })
        assertFalse(motorProntoOffline("", temVozAndroidValida = true) { true })
        assertFalse(lido)
    }

    // ── textoDoCapitulo ──────────────────────────────────────────────────────

    @Test
    fun textoDoCapituloJuntaSoOsParagrafosDele() {
        assertEquals("a\n\nb", textoDoCapitulo(textoDeQuatro(), 0))
        assertEquals("c\n\nd", textoDoCapitulo(textoDeQuatro(), 1))
    }

    @Test
    fun capituloInexistenteDaNulo() {
        assertNull(textoDoCapitulo(textoDeQuatro(), 5))
        assertNull(textoDoCapitulo(textoDeQuatro(), -1))
    }

    @Test
    fun limitesForaDoTextoSaoAjustados() {
        val texto = TextoExtraido(
            capitulos = listOf(Capitulo("Único", -3, 99)),
            paragrafos = textoDeQuatro().paragrafos,
            totalChars = 4,
        )
        assertEquals("a\n\nb\n\nc\n\nd", textoDoCapitulo(texto, 0))
    }

    // ── paragrafoDePartida ───────────────────────────────────────────────────

    @Test
    fun guiadaTocandoEsteLivroDaOParagrafoDePartida() {
        var naTelaLida = false
        val paragrafo = paragrafoDePartida(3, caminho, caminho) { naTelaLida = true; 9 }
        assertEquals(3, paragrafo)
        assertFalse(naTelaLida)
    }

    @Test
    fun guiadaSemIndiceCaiNoParagrafoDaTela() {
        assertEquals(9, paragrafoDePartida(-1, caminho, caminho) { 9 })
    }

    @Test
    fun guiadaDeOutroLivroCaiNoParagrafoDaTela() {
        assertEquals(9, paragrafoDePartida(3, "/livros/outro.txt", caminho) { 9 })
    }

    @Test
    fun semGuiadaUsaOParagrafoDaTela() {
        assertEquals(9, paragrafoDePartida(null, null, caminho) { 9 })
        assertNull(paragrafoDePartida(null, null, caminho) { null })
    }
}
