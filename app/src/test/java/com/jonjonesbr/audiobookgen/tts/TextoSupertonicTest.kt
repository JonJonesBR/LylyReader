package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TextoSupertonicTest {

    @Test
    fun `travessao de abertura e travessao depois de ponto somem`() {
        assertEquals(
            "Sim, senhor. disse Starling.",
            TextoSupertonic.preparar("— Sim, senhor. — disse Starling.")
        )
    }

    @Test
    fun `travessao no meio depois de letra vira virgula`() {
        assertEquals(
            "Lecter nunca sai da cela sem aparelhagem, explicou",
            TextoSupertonic.preparar("— Lecter nunca sai da cela sem aparelhagem — explicou")
        )
    }

    @Test
    fun `travessao depois de virgula some sem virgula dupla`() {
        assertEquals("Sai, explicou", TextoSupertonic.preparar("Sai, — explicou"))
    }

    @Test
    fun `travessao sozinho devolve o texto original`() {
        assertEquals("—", TextoSupertonic.preparar("—"))
    }

    @Test
    fun `texto sem travessao fica igual`() {
        assertEquals("Olá, tudo bem? Sim.", TextoSupertonic.preparar("Olá, tudo bem? Sim."))
    }

    @Test
    fun `hifen de palavra composta fica intacto`() {
        assertEquals("Peguei o guarda-chuva e saí.", TextoSupertonic.preparar("Peguei o guarda-chuva e saí."))
    }

    @Test
    fun `travessao colado em intervalo numerico fica intacto`() {
        assertEquals("Entre 10–20 anos.", TextoSupertonic.preparar("Entre 10–20 anos."))
    }

    @Test
    fun `hifen de abertura com espaco e travessao no fim somem`() {
        assertEquals("Sim.", TextoSupertonic.preparar("- Sim. —"))
    }
}
