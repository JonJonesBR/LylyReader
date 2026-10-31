package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.service.GuidedState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnuncioLivroConcluidoTest {

    private fun estado(index: Int, livroConcluido: Boolean = false) =
        GuidedState(index, isPlaying = false, isLoading = false, livroConcluido = livroConcluido)

    @Test
    fun primeiraEmissaoDepoisDeVoltarNaoAnuncia() {
        // Ao voltar à tela, a primeira emissão repete o estado já concluído: não pode anunciar de novo.
        assertFalse(anunciaLivroConcluido(null, estado(-1, livroConcluido = true)))
    }

    @Test
    fun transicaoDeLendoParaLivroConcluidoAnuncia() {
        assertTrue(anunciaLivroConcluido(estado(5), estado(-1, livroConcluido = true)))
    }

    @Test
    fun jaEstavaConcluidoNaoAnunciaDeNovo() {
        assertFalse(anunciaLivroConcluido(estado(-1, livroConcluido = true), estado(-1, livroConcluido = true)))
    }

    @Test
    fun fecharAGuiadaAMaoNaoAnuncia() {
        assertFalse(anunciaLivroConcluido(estado(5), estado(-1, livroConcluido = false)))
    }

    @Test
    fun paragrafoEmCursoNaoAnuncia() {
        assertFalse(anunciaLivroConcluido(estado(4), estado(5)))
    }
}
