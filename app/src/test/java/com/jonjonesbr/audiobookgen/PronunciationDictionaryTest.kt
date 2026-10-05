package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationDictionaryTest {
    private fun dic(vararg pares: Pair<String, String>) =
        PronunciationDictionary(pares.map { PronunciationEntry(it.first, it.second) })

    @Test fun trocaAPalavraPelaPronuncia() {
        assertEquals("Ela chamou Hermáione.", dic("Hermione" to "Hermáione").apply("Ela chamou Hermione."))
    }

    @Test fun naoDiferenciaMaiusculasENaoTrocaNoMeioDeOutraPalavra() {
        val d = dic("Ana" to "Âna")
        assertEquals("Âna e Âna", d.apply("Ana e ana"))
        assertEquals("banana e Anatólia", d.apply("banana e Anatólia"))
    }

    @Test fun respeitaPontuacaoAoRedorEAcentos() {
        val d = dic("Nyx" to "Níks")
        assertEquals("“Níks”, disse (Níks).", d.apply("“Nyx”, disse (Nyx)."))
        assertEquals("Ação", dic("Xyz" to "Zyx").apply("Ação"))
    }

    @Test fun expressaoMaisLongaVenceEEspacosSaoFlexiveis() {
        val d = dic("Gandalf" to "Gândalf", "Gandalf o Cinzento" to "Gândalf, o Cinzento")
        assertEquals("Gândalf, o Cinzento chegou; Gândalf ficou.", d.apply("Gandalf  o Cinzento chegou; Gandalf ficou."))
    }

    @Test fun trocaEmUmaUnicaPassadaSemReprocessarOResultado() {
        val d = dic("a" to "b", "b" to "c")
        assertEquals("b c", d.apply("a b"))
    }

    @Test fun caracteresEspeciaisSaoLiterais() {
        val d = dic("C++" to "cê mais mais", "R2-D2" to "érre dois dê dois")
        assertEquals("cê mais mais e érre dois dê dois", d.apply("C++ e R2-D2"))
    }

    @Test fun ultimaDefinicaoDaMesmaPalavraVenceEEntradasVaziasSaoIgnoradas() {
        val d = dic("Ana" to "Âna", "ana" to "Ãna", "" to "x", "Zé" to "  ")
        assertEquals(1, d.entries.size)
        assertEquals("Ãna", d.apply("Ana"))
    }

    @Test fun jsonIdaEVolta() {
        val d = dic("Hermione" to "Hermáione", "Nyx" to "Níks")
        val volta = PronunciationDictionary.fromJson(d.toJson())
        assertEquals(d.entries.toSet(), volta.entries.toSet())
        assertEquals("Níks", volta.apply("Nyx"))
    }

    @Test fun jsonInvalidoOuVazioViraDicionarioVazio() {
        assertTrue(PronunciationDictionary.fromJson(null).isEmpty())
        assertTrue(PronunciationDictionary.fromJson("").isEmpty())
        assertTrue(PronunciationDictionary.fromJson("{não é json").isEmpty())
        assertEquals("texto", PronunciationDictionary.EMPTY.apply("texto"))
    }

    @Test fun comESemEntradasNaoMudamOOriginal() {
        val d = dic("Hermione" to "Hermáione")
        assertEquals("Hermáione", d.with(PronunciationEntry("Hermione", "Hermáione")).apply("Hermione"))
        val sem = d.without("hermione")
        assertTrue(sem.isEmpty())
        assertNull(sem.find("Hermione"))
        assertEquals("Hermione", sem.apply("Hermione"))
    }
}
