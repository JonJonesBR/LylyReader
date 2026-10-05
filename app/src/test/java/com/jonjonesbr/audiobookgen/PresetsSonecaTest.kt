package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.proximoPresetSonecaMin
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetsSonecaTest {

    @Test fun desligadaVaiParaOPrimeiroPreset() {
        assertEquals(5, proximoPresetSonecaMin(null))
        assertEquals(5, proximoPresetSonecaMin(0))
    }

    @Test fun avancaParaOProximoPresetDaLista() {
        assertEquals(10, proximoPresetSonecaMin(5))
        assertEquals(20, proximoPresetSonecaMin(10))
        assertEquals(120, proximoPresetSonecaMin(90))
    }

    @Test fun ultimoPresetCicloVoltaParaDesligado() {
        assertEquals(0, proximoPresetSonecaMin(120))
    }

    @Test fun valorPersonalizadoAvancaParaOProximoPresetMaior() {
        // ex.: soneca ligada com um valor personalizado (slider) que não é um preset exato.
        assertEquals(60, proximoPresetSonecaMin(35))
    }

    @Test fun valorPersonalizadoAcimaDoUltimoPresetDesliga() {
        assertEquals(0, proximoPresetSonecaMin(150))
    }
}
