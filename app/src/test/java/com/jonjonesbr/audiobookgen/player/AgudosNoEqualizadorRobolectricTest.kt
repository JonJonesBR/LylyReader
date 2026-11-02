package com.jonjonesbr.audiobookgen.player

import com.jonjonesbr.audiobookgen.ui.LeitorTesteApplication
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rb-2: ciclo de vida do [AgudosNoEqualizador] com o Robolectric. Não afirma que o equalizador ficou ligado: no SDK 34
 * o `ShadowAudioEffect` não emula o Equalizer, então o resultado é imprevisível.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class AgudosNoEqualizadorRobolectricTest {

    @Test
    fun sessaoInvalidaNaoLiga() {
        val agudos = AgudosNoEqualizador()
        agudos.aplicar(0, 6)
        assertFalse(agudos.ligado)
        agudos.aplicar(-1, 6)
        assertFalse(agudos.ligado)
    }

    @Test
    fun semAgudosNaoLiga() {
        val agudos = AgudosNoEqualizador()
        agudos.aplicar(1234, 0)
        assertFalse(agudos.ligado)
    }

    @Test
    fun aplicarELiberarNaoLancam() {
        val agudos = AgudosNoEqualizador()
        agudos.aplicar(1234, 6)
        agudos.liberar()
        assertFalse(agudos.ligado)
    }

    @Test
    fun liberarDuasVezesSemAplicarNaoLanca() {
        val agudos = AgudosNoEqualizador()
        agudos.liberar()
        agudos.liberar()
        assertFalse(agudos.ligado)
    }
}
