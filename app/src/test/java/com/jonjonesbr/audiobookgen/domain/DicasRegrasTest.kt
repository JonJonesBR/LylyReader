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

    @Test
    fun `no leitor a dica dos capitulos vem depois da do menu`() {
        assertEquals(Dica.LEITOR_GUIADA, proxima(TelaDica.LEITOR))
        assertEquals(Dica.LEITOR_CAPITULOS, proxima(TelaDica.LEITOR, setOf("leitor_guiada")))
    }

    @Test
    fun `as dicas das barras recolhidas vem no fim da tela do leitor, na ordem dela`() {
        val doLeitor = Dica.values().filter { it.tela == TelaDica.LEITOR }.map { it.id }
        assertEquals(
            listOf(
                "leitor_guiada", "leitor_capitulos", "leitor_voz_e_motor", "leitor_tela_cheia",
                "leitor_tela_cheia_vertical", "leitor_painel_tira",
            ),
            doLeitor,
        )
        assertEquals(Dica.LEITOR_PAINEL_TIRA, Dica.values().last())
    }

    @Test
    fun `a dica da tela cheia vertical sai depois das outras do leitor`() {
        val vistas = setOf("leitor_guiada", "leitor_capitulos", "leitor_voz_e_motor", "leitor_tela_cheia")
        assertEquals(Dica.LEITOR_TELA_CHEIA_VERTICAL, proxima(TelaDica.LEITOR, vistas))
        assertEquals(Dica.LEITOR_PAINEL_TIRA, proxima(TelaDica.LEITOR, vistas + "leitor_tela_cheia_vertical"))
    }
}
