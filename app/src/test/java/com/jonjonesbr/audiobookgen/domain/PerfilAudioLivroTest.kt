package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfilAudioLivroTest {

    @Test
    fun `json de ida e volta`() {
        val p = PerfilAudioLivro(tomMeiosTons = -2, agudosDb = 3, tomNasFalasMeiosTons = 1, pausaFinalFraseMs = 700, bufferAdiante = 4)
        assertEquals(p, PerfilAudioLivro.fromJson(p.toJson()))
    }

    @Test
    fun `json invalido ou vazio nao vira perfil`() {
        assertNull(PerfilAudioLivro.fromJson(null))
        assertNull(PerfilAudioLivro.fromJson(""))
        assertNull(PerfilAudioLivro.fromJson("isso nao e json"))
    }

    @Test
    fun `fala e narracao dentro do mesmo paragrafo`() {
        val paragrafo = "— Venha logo — disse ela. Depois saiu correndo pela porta."
        val partes = OfflineSpeakerAttributor.partsOf(paragrafo)
        assertTrue(partes.any { it.speech })
        val oracao1 = "— Venha logo — disse ela."
        val oracao2 = "Depois saiu correndo pela porta."
        val total = oracao1.length + oracao2.length
        assertTrue(FalaNoTrecho.fracaoDeFala(partes, 0, oracao1.length, total) > 0.3f)
        assertFalse(FalaNoTrecho.ehFala(partes, oracao1.length, oracao2.length, total))
    }

    @Test
    fun `paragrafo sem fala nunca e fala`() {
        val partes = OfflineSpeakerAttributor.partsOf("Era uma vez um reino distante.")
        assertEquals(0f, FalaNoTrecho.fracaoDeFala(partes, 0, 10, 30), 0.001f)
    }
}
