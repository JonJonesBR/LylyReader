package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusTest {

    @Test
    fun `motores android onnx kokoro e pocket nao exigem rede`() {
        assertFalse(NetworkStatus.engineRequiresNetwork("android"))
        assertFalse(NetworkStatus.engineRequiresNetwork("onnx"))
        assertFalse(NetworkStatus.engineRequiresNetwork("kokoro"))
        assertFalse(NetworkStatus.engineRequiresNetwork("pocket"))
    }

    @Test
    fun `motores online exigem rede`() {
        assertTrue(NetworkStatus.engineRequiresNetwork("edge"))
        assertTrue(NetworkStatus.engineRequiresNetwork("gemini"))
        assertTrue(NetworkStatus.engineRequiresNetwork("openrouter"))
    }

    @Test
    fun `motor desconhecido exige rede por padrao`() {
        assertTrue(NetworkStatus.engineRequiresNetwork(""))
        assertTrue(NetworkStatus.engineRequiresNetwork("motor-inexistente"))
    }
}
