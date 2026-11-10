package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Backup das prefs: o ExoPlayer saiu da leitura guiada (rb-13), e backups antigos com `player_engine`
 * ainda restauram.
 */
class AppPrefsBackupTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private fun prefs(raiz: File = pasta.root): Pair<AppPrefs, PrefsEmMemoria> {
        val ctx = PrefsEmMemoria.contexto(raiz)
        val gravado = ctx.getSharedPreferences(AppPrefs.NAME, 0) as PrefsEmMemoria
        return AppPrefs(ctx) to gravado
    }

    @Test
    fun `backup antigo com player_engine restaura sem erro`() {
        val (appPrefs, gravado) = prefs()
        val json = JSONObject()
            .put("player_engine", "exoplayer")
            .put("ritmo", 77)
            .put("guided_speed_mult", 1.25)

        AppPrefsBackup.aplicar(appPrefs, json)

        assertEquals(77, appPrefs.ritmo)
        assertEquals(1.25f, appPrefs.guidedSpeedMult, 1e-6f)
        assertFalse(gravado.contains("player_engine"))
    }

    @Test
    fun `backup novo nao exporta player_engine`() {
        val (appPrefs, _) = prefs()

        assertFalse(AppPrefsBackup.paraJson(appPrefs).has("player_engine"))
    }

    @Test
    fun `ida e volta mantem o ritmo`() {
        val (origem, _) = prefs()
        origem.ritmo = 77
        val json = AppPrefsBackup.paraJson(origem)

        val (destino, _) = prefs(File(pasta.root, "b"))
        AppPrefsBackup.aplicar(destino, json)

        assertEquals(77, destino.ritmo)
    }

    @Test
    fun `ida e volta mantem o player por AudioTrack`() {
        val (origem, _) = prefs()
        origem.playerAudioTrack = true
        val json = AppPrefsBackup.paraJson(origem)

        val (destino, _) = prefs(File(pasta.root, "c"))
        assertFalse(destino.playerAudioTrack)
        AppPrefsBackup.aplicar(destino, json)

        assertTrue(destino.playerAudioTrack)
    }

    @Test
    fun `backup exporta e restaura o estilo de ritmo`() {
        val (origem, _) = prefs()
        origem.estiloDeRitmo = EstiloDeRitmo.CALMA
        val json = AppPrefsBackup.paraJson(origem)
        assertEquals("calma", json.getString("estilo_ritmo"))

        val (destino, _) = prefs(File(pasta.root, "c"))
        AppPrefsBackup.aplicar(destino, json)

        assertEquals(EstiloDeRitmo.CALMA, destino.estiloDeRitmo)
    }

    @Test
    fun `backup antigo so com entonacao continua restaura Fluida`() {
        val (appPrefs, _) = prefs()
        val json = JSONObject().put("entonacao_continua", true)

        AppPrefsBackup.aplicar(appPrefs, json)

        assertEquals(EstiloDeRitmo.FLUIDA, appPrefs.estiloDeRitmo)
    }
}
