package com.jonjonesbr.audiobookgen.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdDeLivroValidoTest {

    @Test
    fun `id hexadecimal comum e aceito`() {
        assertTrue(idDeLivroValido("a1b2c3d4e5f6"))
    }

    @Test
    fun `id vazio e recusado`() {
        assertFalse(idDeLivroValido(""))
    }

    @Test
    fun `id com barra ou barra invertida e recusado`() {
        assertFalse(idDeLivroValido("abc/def"))
        assertFalse(idDeLivroValido("abc\\def"))
    }

    @Test
    fun `id com dois pontos seguidos e recusado`() {
        assertFalse(idDeLivroValido(".."))
        assertFalse(idDeLivroValido("abc..def"))
    }
}
