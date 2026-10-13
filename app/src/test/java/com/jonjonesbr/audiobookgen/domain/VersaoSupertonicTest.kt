package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VersaoSupertonicTest {

    private val ingles = "The quick brown fox jumps over the lazy dog. This is an example of English text."
    private val portugues = "O Brasil é um país de dimensões continentais e a cultura é muito rica."
    private val espanhol = "El español es una lengua romance muy extendida por todo el mundo."

    @Test fun vozPtComTextoInglesFicaNaV2() {
        assertEquals("v2", versaoSupertonic("supertonic-f4-pt", ingles))
        assertEquals("v2", versaoSupertonic("supertonic-m1-pt", ingles))
    }

    @Test fun f1EM1ComInglesClaroUsamV1() {
        assertEquals("v1", versaoSupertonic("supertonic-f1", ingles))
        assertEquals("v1", versaoSupertonic("supertonic-m1", ingles))
    }

    @Test fun f1EM1ComPortuguesFicamNaV2() {
        assertEquals("v2", versaoSupertonic("supertonic-f1", portugues))
        assertEquals("v2", versaoSupertonic("supertonic-m1", portugues))
    }

    @Test fun f1EM1ComEspanholFicamNaV2() {
        assertEquals("v2", versaoSupertonic("supertonic-f1", espanhol))
        assertEquals("v2", versaoSupertonic("supertonic-m1", espanhol))
    }

    @Test fun semEvidenciaOuTextoVazioFicaNaV2() {
        assertEquals("v2", versaoSupertonic("supertonic-f1", ""))
        assertEquals("v2", versaoSupertonic("supertonic-m1", "xyz qwe 123 ..."))
    }

    @Test fun vozesF2AF5EM2AM5SempreV2MesmoComInglês() {
        for (n in 2..5) {
            assertEquals("v2", versaoSupertonic("supertonic-f$n", ingles))
            assertEquals("v2", versaoSupertonic("supertonic-m$n", ingles))
        }
    }

    @Test fun idsDeOutrosMotoresFicamNaV2() {
        assertEquals("v2", versaoSupertonic("kokoro-pf-dora", ingles))
        assertEquals("v2", versaoSupertonic("pt-BR-ThalitaMultilingualNeural", ingles))
        assertEquals("v2", versaoSupertonic("elevenlabs-21m00Tcm4TlvDq8ikWAM", ingles))
        assertEquals("v2", versaoSupertonic("pocket-en-alba", ingles))
    }

    @Test fun efetivaComV1ProntoUsaV1SoParaInglesEmF1M1() {
        assertEquals("v1", versaoEfetivaSupertonic("supertonic-f1", ingles, v1Pronto = true))
        assertEquals("v1", versaoEfetivaSupertonic("supertonic-m1", ingles, v1Pronto = true))
        assertEquals("v2", versaoEfetivaSupertonic("supertonic-f1", portugues, v1Pronto = true))
        assertEquals("v2", versaoEfetivaSupertonic("supertonic-f4-pt", ingles, v1Pronto = true))
    }

    @Test fun efetivaSemV1ProntoCaiParaV2() {
        assertEquals("v2", versaoEfetivaSupertonic("supertonic-f1", ingles, v1Pronto = false))
        assertEquals("v2", versaoEfetivaSupertonic("supertonic-m1", ingles, v1Pronto = false))
        assertEquals("v2", versaoEfetivaSupertonic("supertonic-f1", portugues, v1Pronto = false))
    }

    @Test fun idiomaNativoV1SempreEnMesmoComTextoPortugues() {
        assertEquals("en", idiomaNativoSupertonic("supertonic-f1", portugues, "v1"))
        assertEquals("en", idiomaNativoSupertonic("supertonic-m1", espanhol, "v1"))
    }

    @Test fun idiomaNativoV1ComCapituloEmInglesFicaEn() {
        assertEquals("en", idiomaNativoSupertonic("supertonic-f1", "Chapter IV.", "v1"))
    }

    @Test fun idiomaNativoV2VozPtForcaPt() {
        assertEquals("pt", idiomaNativoSupertonic("supertonic-f4-pt", espanhol, "v2"))
        assertEquals("pt", idiomaNativoSupertonic("supertonic-M2-PT", ingles, "v2"))
    }

    @Test fun idiomaNativoV2MultilingueSegueTexto() {
        assertEquals("es", idiomaNativoSupertonic("supertonic-f1", espanhol, "v2"))
        assertEquals("pt", idiomaNativoSupertonic("supertonic-m1", portugues, "v2"))
        assertEquals("en", idiomaNativoSupertonic("supertonic-m1", ingles, "v2"))
    }

    @Test fun eDeterministicaParaOMesmoPar() {
        assertEquals(versaoSupertonic("supertonic-f1", ingles), versaoSupertonic("supertonic-f1", ingles))
    }
}
