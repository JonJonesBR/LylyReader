package com.jonjonesbr.audiobookgen.util

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolhaInferiorTest {

    @Test
    fun paisagemExpandeAFolha() {
        assertTrue(precisaExpandirFolha(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun retratoMantemOComportamentoPadrao() {
        assertFalse(precisaExpandirFolha(Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun orientacaoIndefinidaNaoExpande() {
        assertFalse(precisaExpandirFolha(Configuration.ORIENTATION_UNDEFINED))
    }
}
