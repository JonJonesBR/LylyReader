package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.domain.FonteLeitura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fonte nova do leitor (migrada da serifada liga/desliga) e os espaços entre letras e parágrafos. */
class AppPrefsTipografiaTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private fun prefs(): Pair<AppPrefs, PrefsEmMemoria> {
        val ctx = PrefsEmMemoria.contexto(pasta.root)
        val gravado = ctx.getSharedPreferences(AppPrefs.NAME, 0) as PrefsEmMemoria
        return AppPrefs(ctx) to gravado
    }

    @Test
    fun `sem nada gravado a fonte e os espacos sao os padroes`() {
        val (appPrefs, _) = prefs()

        assertEquals("PADRAO", appPrefs.readerFonteTipo)
        assertEquals(0, appPrefs.readerEspacoLetras)
        assertEquals(0, appPrefs.readerEspacoParagrafos)
    }

    @Test
    fun `quem tinha a serifada ligada continua com ela`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putBoolean("reader_fonte_serifada", true).apply()

        assertEquals("SERIFADA", appPrefs.readerFonteTipo)
    }

    @Test
    fun `a fonte nova gravada prevalece sobre a serifada antiga`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putBoolean("reader_fonte_serifada", true).apply()
        appPrefs.readerFonteTipo = "DISLEXIA"

        assertEquals("DISLEXIA", appPrefs.readerFonteTipo)
    }

    @Test
    fun `salvar a aparencia grava a fonte nova e os dois espacos`() {
        val (appPrefs, gravado) = prefs()

        appPrefs.salvarAparenciaLeitor(
            fonte = 18f,
            espacamento = 1.5f,
            temaOrdinal = 0,
            fonteTipo = FonteLeitura.DISLEXIA,
            margemNivel = 1,
            rolagemHorizontal = false,
            espacoLetras = 2,
            espacoParagrafos = 1
        )

        assertEquals("DISLEXIA", gravado.getString("reader_fonte_tipo", null))
        assertEquals(2, appPrefs.readerEspacoLetras)
        assertEquals(1, appPrefs.readerEspacoParagrafos)
        assertFalse(gravado.contains("reader_fonte_serifada"))
    }
}
