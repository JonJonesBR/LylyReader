package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TipografiaLeituraTest {

    @Test
    fun `sem nome gravado e sem a serifada antiga a fonte e a padrao`() {
        assertEquals(FonteLeitura.PADRAO, FonteLeitura.derivar(null, serifadaAntiga = false))
    }

    @Test
    fun `serifada antiga ligada, sem a preferencia nova, vira serifada`() {
        assertEquals(FonteLeitura.SERIFADA, FonteLeitura.derivar(null, serifadaAntiga = true))
    }

    @Test
    fun `preferencia nova prevalece sobre a antiga`() {
        assertEquals(FonteLeitura.DISLEXIA, FonteLeitura.derivar("DISLEXIA", serifadaAntiga = true))
        assertEquals(FonteLeitura.PADRAO, FonteLeitura.derivar("PADRAO", serifadaAntiga = true))
    }

    @Test
    fun `nome ausente ou desconhecido volta a padrao`() {
        assertEquals(FonteLeitura.PADRAO, FonteLeitura.porNome(null))
        assertEquals(FonteLeitura.PADRAO, FonteLeitura.porNome("COMIC"))
    }

    @Test
    fun `le cada fonte pelo nome`() {
        FonteLeitura.entries.forEach { assertEquals(it, FonteLeitura.porNome(it.name)) }
    }

    @Test
    fun `espaco entre letras cresce de normal a largo`() {
        assertEquals(0f, espacoLetrasEm(0), 0f)
        assertEquals(0.05f, espacoLetrasEm(1), 0f)
        assertEquals(0.12f, espacoLetrasEm(2), 0f)
    }

    @Test
    fun `espaco entre paragrafos usa o padding original no normal`() {
        assertEquals(6, espacoParagrafosDp(0))
        assertEquals(12, espacoParagrafosDp(1))
        assertEquals(20, espacoParagrafosDp(2))
    }

    @Test
    fun `campo de edicao mantem os 12 dp de antes no normal e soma o extra do nivel`() {
        assertEquals(12, espacoParagrafosEdicaoDp(0))
        assertEquals(18, espacoParagrafosEdicaoDp(1))
        assertEquals(26, espacoParagrafosEdicaoDp(2))
        assertEquals(12, espacoParagrafosEdicaoDp(-1))
        assertEquals(26, espacoParagrafosEdicaoDp(5))
    }

    @Test
    fun `nivel fora do intervalo cai no mais proximo`() {
        assertEquals(0f, espacoLetrasEm(-3), 0f)
        assertEquals(0.12f, espacoLetrasEm(7), 0f)
        assertEquals(6, espacoParagrafosDp(-1))
        assertEquals(20, espacoParagrafosDp(5))
    }

    @Test
    fun `tipografia sem nada escolhido e a padrao sem espacos`() {
        val padrao = TipografiaLeitor()

        assertEquals(FonteLeitura.PADRAO, padrao.fonte)
        assertEquals(ESPACO_NIVEL_PADRAO, padrao.espacoLetras)
        assertEquals(ESPACO_NIVEL_PADRAO, padrao.espacoParagrafos)
    }

    @Test
    fun `tipografia so e diferente quando algum campo muda`() {
        val base = TipografiaLeitor(FonteLeitura.SERIFADA, 1, 2)

        assertEquals(base, TipografiaLeitor(FonteLeitura.SERIFADA, 1, 2))
        assertNotEquals(base, base.copy(fonte = FonteLeitura.DISLEXIA))
        assertNotEquals(base, base.copy(espacoLetras = 0))
        assertNotEquals(base, base.copy(espacoParagrafos = 1))
    }

    @Test
    fun `o estado da tela expoe a tipografia que o adapter recebe`() {
        val estado = ReaderUiState(fonte = FonteLeitura.DISLEXIA, espacoLetras = 2, espacoParagrafos = 1)

        assertEquals(TipografiaLeitor(FonteLeitura.DISLEXIA, 2, 1), estado.tipografia)
    }
}
