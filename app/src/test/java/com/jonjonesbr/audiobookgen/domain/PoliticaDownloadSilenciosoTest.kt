package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticaDownloadSilenciosoTest {

    private val tamanho = 300L * 1024L * 1024L
    private val espacoDeSobra = 2L * tamanho

    @Test
    fun deveIniciarQuandoWifiEEspacoSuficiente() {
        assertTrue(PoliticaDownloadSilencioso.deveIniciar(false, false, espacoDeSobra, tamanho))
    }

    @Test
    fun naoIniciaSeJaFoiIniciadoAntes() {
        assertFalse(PoliticaDownloadSilencioso.deveIniciar(true, false, espacoDeSobra, tamanho))
    }

    @Test
    fun naoIniciaEmRedeTarifada() {
        assertFalse(PoliticaDownloadSilencioso.deveIniciar(false, true, espacoDeSobra, tamanho))
    }

    @Test
    fun naoIniciaSemDoDoEspacoLivreExigido() {
        assertFalse(PoliticaDownloadSilencioso.deveIniciar(false, false, espacoDeSobra - 1L, tamanho))
    }

    @Test
    fun iniciaNoLimiteExatoDoEspacoLivre() {
        assertTrue(PoliticaDownloadSilencioso.deveIniciar(false, false, espacoDeSobra, tamanho))
    }
}
