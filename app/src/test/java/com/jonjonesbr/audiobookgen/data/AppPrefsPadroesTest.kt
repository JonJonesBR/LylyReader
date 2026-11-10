package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Padrões novos (Supertonic 10, Pocket 10, ritmo Natural) e as migrações de uma vez só. */
class AppPrefsPadroesTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private fun prefs(): Pair<AppPrefs, PrefsEmMemoria> {
        val ctx = PrefsEmMemoria.contexto(pasta.root)
        val gravado = ctx.getSharedPreferences(AppPrefs.NAME, 0) as PrefsEmMemoria
        return AppPrefs(ctx) to gravado
    }

    @Test
    fun `padroes novos sem nada gravado`() {
        val (appPrefs, _) = prefs()

        assertEquals(10, appPrefs.supertonicSteps)
        assertEquals(10, appPrefs.pocketPassos)
        assertFalse(appPrefs.pausasNaturais)
    }

    @Test
    fun `supertonic gravado com 8 vai para 18 e a chave some`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putInt("supertonic_steps", 8).apply()

        assertEquals(10, appPrefs.supertonicSteps)
        assertFalse(gravado.contains("supertonic_steps"))
    }

    @Test
    fun `supertonic gravado com 5 (versao muito antiga) chega a 18 pela cadeia`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putInt("supertonic_steps", 5).apply()

        assertEquals(10, appPrefs.supertonicSteps)
        assertFalse(gravado.contains("supertonic_steps"))
    }

    @Test
    fun `supertonic com valor escolhido pelo usuario fica`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putInt("supertonic_steps", 12).apply()

        assertEquals(12, appPrefs.supertonicSteps)
        assertEquals(12, gravado.getInt("supertonic_steps", -1))
    }

    @Test
    fun `supertonic escolhido depois da migracao nao e tocado de novo`() {
        val (appPrefs, gravado) = prefs()
        assertEquals(10, appPrefs.supertonicSteps) // roda as migrações
        appPrefs.supertonicSteps = 8

        assertEquals(8, appPrefs.supertonicSteps)
        assertTrue(gravado.getBoolean("supertonic_steps_padrao18", false))
    }

    @Test
    fun `pocket gravado com 4 vai para 10`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putInt("pocket_passos", 4).apply()

        assertEquals(10, appPrefs.pocketPassos)
        assertFalse(gravado.contains("pocket_passos"))
    }

    @Test
    fun `pocket gravado com 1 (padrao muito antigo) vai para 10`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putInt("pocket_passos", 1).apply()

        assertEquals(10, appPrefs.pocketPassos)
        assertFalse(gravado.contains("pocket_passos"))
    }

    @Test
    fun `pocket escolhido pelo usuario fica, inclusive acima do antigo maximo`() {
        val (appPrefs, _) = prefs()
        appPrefs.pocketPassos = 7
        assertEquals(7, appPrefs.pocketPassos)

        appPrefs.pocketPassos = 20
        assertEquals(20, appPrefs.pocketPassos)
        appPrefs.pocketPassos = 25
        assertEquals(20, appPrefs.pocketPassos)
    }

    @Test
    fun `pausas naturais gravadas como true sao escolha do usuario e ficam`() {
        val (appPrefs, _) = prefs()
        appPrefs.pausasNaturais = true

        assertTrue(appPrefs.pausasNaturais)
    }

    @Test
    fun `ritmo sem nada gravado e Natural`() {
        val (appPrefs, _) = prefs()

        assertEquals(EstiloDeRitmo.NATURAL, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `migracao do ritmo a partir da entonacao continua ligada`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putBoolean("entonacao_continua", true).apply()

        assertEquals(EstiloDeRitmo.FLUIDA, appPrefs.estiloDeRitmo)
        assertEquals("fluida", gravado.getString("estilo_ritmo", null))
    }

    @Test
    fun `migracao do ritmo a partir da pausa natural desligada de proposito`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putBoolean("pausas_naturais", false).apply()

        assertEquals(EstiloDeRitmo.FLUIDA, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `migracao do ritmo com pausa natural ligada vai para Natural`() {
        val (appPrefs, _) = prefs()
        appPrefs.pausasNaturais = true

        assertEquals(EstiloDeRitmo.NATURAL, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `a migracao do ritmo roda uma vez e persiste`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putBoolean("entonacao_continua", true).apply()
        assertEquals(EstiloDeRitmo.FLUIDA, appPrefs.estiloDeRitmo)

        gravado.edit().putBoolean("entonacao_continua", false).apply()

        assertEquals(EstiloDeRitmo.FLUIDA, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `estilo de ritmo faz ida e volta`() {
        val (appPrefs, _) = prefs()
        appPrefs.estiloDeRitmo = EstiloDeRitmo.CALMA

        assertEquals(EstiloDeRitmo.CALMA, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `ritmo corrompido cai em Natural`() {
        val (appPrefs, gravado) = prefs()
        gravado.edit().putString("estilo_ritmo", "lixo").apply()

        assertEquals(EstiloDeRitmo.NATURAL, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `restaurar o padrao do som volta o ritmo para Natural`() {
        val (appPrefs, gravado) = prefs()
        appPrefs.estiloDeRitmo = EstiloDeRitmo.FLUIDA
        gravado.edit().putBoolean("entonacao_continua", true).apply()

        appPrefs.restaurarPadroesDeSom()

        assertEquals(EstiloDeRitmo.NATURAL, appPrefs.estiloDeRitmo)
    }

    @Test
    fun `rapida escolhida antes da migracao dos passos nao e apagada`() {
        val (appPrefs, gravado) = prefs()
        appPrefs.supertonicSteps = 8

        assertEquals(8, appPrefs.supertonicSteps)
        assertEquals(8, gravado.getInt("supertonic_steps", -1))
    }
}
