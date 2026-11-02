package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerguntasDeCapitulosTest {

    @Test fun semCapituloNaoPergunta() {
        assertFalse(valePerguntarPorCapitulo(0))
    }

    @Test fun umSoCapituloNaoPergunta() {
        // TXT sem divisão: o único capítulo é "Texto completo", e perguntar só repete o livro inteiro.
        assertFalse(valePerguntarPorCapitulo(1))
    }

    @Test fun variosCapitulosPerguntam() {
        assertTrue(valePerguntarPorCapitulo(2))
        assertTrue(valePerguntarPorCapitulo(40))
    }
}
