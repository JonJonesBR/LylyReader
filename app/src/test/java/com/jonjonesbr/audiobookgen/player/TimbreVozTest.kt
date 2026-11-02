package com.jonjonesbr.audiobookgen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** rb-2: contas do timbre (tom em meios-tons e agudos) usadas pelos motores da leitura guiada. */
class TimbreVozTest {

    @Test
    fun padraoNaoAltera() {
        assertEquals(TimbreVoz(0, 0), TimbreVoz.PADRAO)
        assertFalse(TimbreVoz.PADRAO.ativo)
        assertEquals(1f, TimbreVoz.PADRAO.fatorDeTom, 0f)
    }

    @Test
    fun fatorDeTomSegueOSemitom() {
        // Um semitom = 2^(1/12); dois meios-tons = um semitom.
        assertEquals(1.0594631f, TimbreVoz(2, 0).fatorDeTom, 1e-5f)
        assertEquals(2f, TimbreVoz(24, 0).fatorDeTom, 0f)
        assertEquals(0.5f, TimbreVoz(-24, 0).fatorDeTom, 0f)
    }

    @Test
    fun semitonsVemDeMeiosTons() {
        assertEquals(1.5f, TimbreVoz(3, 0).semitons, 0f)
        assertEquals(-0.5f, TimbreVoz(-1, 0).semitons, 0f)
    }

    @Test
    fun ativoComSoTomOuSoAgudos() {
        assertTrue(TimbreVoz(2, 0).ativo)
        assertTrue(TimbreVoz(0, 4).ativo)
    }

    @Test
    fun agudosNegativosContinuamAtivos() {
        // Comportamento atual: o modelo não rejeita agudos negativos. As prefs limitam o valor a 0..8.
        assertTrue(TimbreVoz(0, -1).ativo)
    }
}
