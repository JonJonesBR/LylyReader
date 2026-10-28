package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketThreadsTest {

    @Test
    fun `sem o experimental usa sempre 2 threads`() {
        listOf(1, 2, 4, 8, 16).forEach { nucleos ->
            assertEquals(2, PocketThreads.para(nucleos, experimental = false))
        }
    }

    @Test
    fun `experimental usa metade dos nucleos`() {
        assertEquals(3, PocketThreads.para(6, experimental = true))
        assertEquals(4, PocketThreads.para(8, experimental = true))
    }

    @Test
    fun `experimental fica entre 2 e 4 threads`() {
        assertEquals(2, PocketThreads.para(1, experimental = true))
        assertEquals(2, PocketThreads.para(4, experimental = true))
        assertEquals(4, PocketThreads.para(16, experimental = true))
    }
}
