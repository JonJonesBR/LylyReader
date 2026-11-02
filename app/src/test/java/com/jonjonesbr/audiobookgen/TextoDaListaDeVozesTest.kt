package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.TextoDaListaDeVozes
import org.junit.Assert.assertEquals
import org.junit.Test

class TextoDaListaDeVozesTest {

    @Test fun tiraOMotorDoNomeQuandoEleVaiNaLinhaDeDetalhe() {
        assertEquals("Jeff", TextoDaListaDeVozes.nomeSemMotor("Jeff (Piper media)", "Piper"))
        assertEquals("F1 PT", TextoDaListaDeVozes.nomeSemMotor("F1 PT (Supertonic 3)", "Supertonic"))
    }

    @Test fun mantemParentesesQueNaoSaoOMotor() {
        assertEquals("Português (MMS)", TextoDaListaDeVozes.nomeSemMotor("Português (MMS)", "Kokoro"))
        assertEquals("Dora", TextoDaListaDeVozes.nomeSemMotor("Dora", "Kokoro"))
    }

    @Test fun semMotorOuComMotorVazioONomeNaoMuda() {
        assertEquals("Jeff (Piper media)", TextoDaListaDeVozes.nomeSemMotor("Jeff (Piper media)", ""))
        assertEquals("Jeff (Piper media)", TextoDaListaDeVozes.nomeSemMotor("Jeff (Piper media)", "  "))
    }

    @Test fun numeraCadaVozDentroDoSeuIdioma() {
        val numeros = TextoDaListaDeVozes.numeroPorIdioma(listOf("pt-BR", "en-US", "pt-BR", "pt-BR", "en-US"))
        assertEquals(listOf(1, 1, 2, 3, 2), numeros)
    }

    @Test fun listaVaziaNaoTemNumeros() {
        assertEquals(emptyList<Int>(), TextoDaListaDeVozes.numeroPorIdioma(emptyList()))
    }

    @Test fun linhaDeDetalheJuntaSoAsPartesPreenchidas() {
        assertEquals(
            "pt-BR · Edge · feminina · precisa de internet",
            TextoDaListaDeVozes.linhaDeDetalhe("pt-BR", "Edge", "feminina", "precisa de internet", null),
        )
        assertEquals(
            "Piper · offline",
            TextoDaListaDeVozes.linhaDeDetalhe(null, "Piper", null, "offline", "  "),
        )
    }
}
