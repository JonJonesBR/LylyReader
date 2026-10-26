package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TextoPocketTest {

    @Test fun `reticencias no meio viram virgula`() {
        assertEquals("Ele disse, e saiu.", TextoPocket.normalizar("Ele disse... e saiu."))
        assertEquals("Ele disse, e saiu.", TextoPocket.normalizar("Ele disse… e saiu."))
        assertEquals("Ele disse, e saiu.", TextoPocket.normalizar("Ele disse . . . e saiu."))
    }

    @Test fun `reticencias no fim viram ponto`() {
        assertEquals("Não sei.", TextoPocket.normalizar("Não sei..."))
        assertEquals("— Não sei.”", TextoPocket.normalizar("— Não sei…”"))
    }

    @Test fun `antes de nova frase viram ponto`() {
        assertEquals("Bem. Talvez amanhã.", TextoPocket.normalizar("Bem... Talvez amanhã."))
    }

    @Test fun `no comeco somem`() {
        assertEquals("e então ele voltou.", TextoPocket.normalizar("...e então ele voltou."))
        assertEquals("“e então.”", TextoPocket.normalizar("“…e então.”"))
    }

    @Test fun `antes de interrogacao ou exclamacao somem`() {
        assertEquals("Você?", TextoPocket.normalizar("Você...?"))
        assertEquals("Não!", TextoPocket.normalizar("Não…!"))
    }

    @Test fun `pontuacao repetida vira uma so`() {
        assertEquals("O quê? Sério!", TextoPocket.normalizar("O quê?!? Sério!!!"))
    }

    @Test fun `texto comum e numeros ficam iguais`() {
        val texto = "Custou 3.5 reais, e o Sr. Silva pagou. Fim."
        assertEquals(texto, TextoPocket.normalizar(texto))
    }
}
