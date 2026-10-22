package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControladorConfiavelTest {

    private val meuUid = 10_100

    @Test fun controladorConfiavelPodeNavegar() {
        assertTrue(podeNavegar("com.qualquer.app", 20_000, meuUid, confiavel = true))
    }

    @Test fun mesmoUidDoAppPodeNavegar() {
        assertTrue(podeNavegar("com.jonjonesbr.audiobookgen", meuUid, meuUid, confiavel = false))
    }

    @Test fun androidAutoPodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.google.android.projection.gearhead", 20_000, meuUid, confiavel = false))
    }

    @Test fun systemUiPodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.android.systemui", 1_000, meuUid, confiavel = false))
    }

    @Test fun buscaDoGooglePodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.google.android.googlequicksearchbox", 20_000, meuUid, confiavel = false))
    }

    @Test fun bluetoothPodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.android.bluetooth", 1_002, meuUid, confiavel = false))
    }

    @Test fun wearPodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.google.android.wearable.app", 20_000, meuUid, confiavel = false))
    }

    @Test fun assistenteDoCarroPodeNavegarMesmoSemSerTrusted() {
        assertTrue(podeNavegar("com.google.android.carassistant", 20_000, meuUid, confiavel = false))
    }

    @Test fun outroAppNaoConfiavelNaoPodeNavegar() {
        assertFalse(podeNavegar("com.exemplo.espiao", 20_000, meuUid, confiavel = false))
    }

    @Test fun pacoteParecidoComOsConfiaveisNaoPodeNavegar() {
        assertFalse(podeNavegar("com.android.systemui.falso", 20_000, meuUid, confiavel = false))
        assertFalse(podeNavegar("com.google.android.projection.gearhead.clone", 20_000, meuUid, confiavel = false))
        assertFalse(podeNavegar("com.android.bluetooth.falso", 20_000, meuUid, confiavel = false))
        assertFalse(podeNavegar("com.google.android.wearable.app.clone", 20_000, meuUid, confiavel = false))
        assertFalse(podeNavegar("com.google.android.carassistant.clone", 20_000, meuUid, confiavel = false))
    }
}
