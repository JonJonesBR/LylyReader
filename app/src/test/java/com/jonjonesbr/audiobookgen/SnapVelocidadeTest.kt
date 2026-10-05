package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.aplicarSnapVelocidade
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapVelocidadeTest {

    private val presets = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    @Test fun travaNoPresetQuandoBemProximo() {
        assertEquals(1.0f, aplicarSnapVelocidade(1.01f, presets, limiar = 0.04f), 0.001f)
        assertEquals(1.5f, aplicarSnapVelocidade(1.48f, presets, limiar = 0.04f), 0.001f)
    }

    @Test fun travaExatamenteNoValorDoPreset() {
        assertEquals(1.25f, aplicarSnapVelocidade(1.25f, presets, limiar = 0.04f), 0.001f)
    }

    @Test fun mantemValorLivreQuandoLongeDosPresets() {
        assertEquals(1.15f, aplicarSnapVelocidade(1.15f, presets, limiar = 0.04f), 0.001f)
    }

    @Test fun escolhePresetMaisProximoQuandoEntreDois() {
        // 1.24 está mais perto de 1.25 (dist 0.01) que de 1.0 (dist 0.24) — mas fora do limiar
        // padrão em relação a 1.0, só trava se dentro do limiar do mais próximo.
        assertEquals(1.25f, aplicarSnapVelocidade(1.24f, presets, limiar = 0.04f), 0.001f)
    }
}
