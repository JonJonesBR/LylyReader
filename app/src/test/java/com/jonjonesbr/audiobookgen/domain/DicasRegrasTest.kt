package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DicasRegrasTest {
    private fun proxima(tela: TelaDica, vistas: Set<String> = emptySet(), tour: Boolean = true, voz: Boolean = true, novidades: Boolean = false) =
        DicasRegras.proxima(tela, vistas, tour, voz, novidades)

    @Test
    fun `primeira dica da tela e a primeira na ordem`() {
        assertEquals(Dica.BIB_ADICIONAR, proxima(TelaDica.BIBLIOTECA))
        assertEquals(Dica.LIVRO_GERAR, proxima(TelaDica.LIVRO))
    }

    @Test
    fun `depois de vista passa para a seguinte e por fim nenhuma`() {
        assertEquals(Dica.BIB_TEMA, proxima(TelaDica.BIBLIOTECA, setOf("bib_adicionar")))
        assertNull(proxima(TelaDica.BIBLIOTECA, setOf("bib_adicionar", "bib_tema")))
    }

    @Test
    fun `nada antes do tour ou da escolha de voz`() {
        assertNull(proxima(TelaDica.BIBLIOTECA, tour = false))
        assertNull(proxima(TelaDica.BIBLIOTECA, voz = false))
    }

    @Test
    fun `nada enquanto o dialogo de novidades esta pendente`() {
        assertNull(proxima(TelaDica.BIBLIOTECA, novidades = true))
    }

    @Test
    fun `dicas de outra tela nao aparecem`() {
        assertNull(proxima(TelaDica.DOWNLOADS, setOf("downloads_vozes")))
        assertEquals(Dica.DOWNLOADS_VOZES, proxima(TelaDica.DOWNLOADS, setOf("bib_adicionar")))
    }

    @Test
    fun `pode mostrar respeita as mesmas condicoes`() {
        assertTrue(DicasRegras.podeMostrar(Dica.LEITOR_VOZ_E_MOTOR, emptySet(), true, true, false))
        assertFalse(DicasRegras.podeMostrar(Dica.LEITOR_VOZ_E_MOTOR, setOf("leitor_voz_e_motor"), true, true, false))
    }

    @Test
    fun `ids das dicas sao unicos`() {
        assertEquals(Dica.values().size, Dica.values().map { it.id }.toSet().size)
    }
}
