package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AmostraVozUseCaseTest {

    @Test
    fun `nome do cache usa o prefixo do motor e o formato preview_motor_hash_hash wav`() {
        val nome = nomeArquivoCacheAmostra("onnx", "f1", "Olá")
        assertEquals("preview_onnx_${"f1".hashCode()}_${"Olá".hashCode()}.wav", nome)
    }

    @Test
    fun `mesmo motor voz e texto geram o mesmo nome para reaproveitar o cache`() {
        assertEquals(
            nomeArquivoCacheAmostra("android", "voz", "texto"),
            nomeArquivoCacheAmostra("android", "voz", "texto")
        )
    }

    @Test
    fun `texto diferente gera nome diferente`() {
        assertNotEquals(
            nomeArquivoCacheAmostra("android", "voz", "um"),
            nomeArquivoCacheAmostra("android", "voz", "dois")
        )
    }

    @Test
    fun `motores diferentes nunca compartilham o mesmo arquivo`() {
        assertNotEquals(
            nomeArquivoCacheAmostra("android", "voz", "texto"),
            nomeArquivoCacheAmostra("onnx", "voz", "texto")
        )
    }
}
