package com.jonjonesbr.audiobookgen.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.domain.ProgressoEstruturado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class TextoDeProgressoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `primeira passada com falhas diz que serao refeitas automaticamente`() {
        val frase = TextoDeProgresso.frase(context, ProgressoEstruturado(pct = 18, etaSegundos = 168, falhas = 5))
        assertEquals("18% · about 2 min left · 5 parts will be redone automatically", frase)
    }

    @Test
    fun `uma falha usa o singular`() {
        val frase = TextoDeProgresso.frase(context, ProgressoEstruturado(pct = 18, etaSegundos = 168, falhas = 1))
        assertEquals("18% · about 2 min left · 1 part will be redone automatically", frase)
    }

    @Test
    fun `rodada de retry mostra o refazer com a tentativa e sem tempo nem falhas`() {
        val frase = TextoDeProgresso.frase(
            context,
            ProgressoEstruturado(pct = 40, etaSegundos = 100, falhas = 2, refazendo = 2, rodada = 1, rodadas = 3),
        )
        assertEquals("40% · Redoing 2 parts (attempt 1 of 3), please wait…", frase)
    }

    @Test
    fun `rodada de retry com um trecho usa o singular`() {
        val frase = TextoDeProgresso.frase(
            context,
            ProgressoEstruturado(pct = 40, etaSegundos = null, falhas = 0, refazendo = 1, rodada = 2, rodadas = 3),
        )
        assertEquals("40% · Redoing 1 part (attempt 2 of 3), please wait…", frase)
    }

    @Test
    fun `linha de retry vinda do Python vira a frase de refazer`() {
        val linhas = "Capítulo 2 de 4\n@progresso pct=60 refazendo=2 rodada=1 rodadas=3"
        assertEquals(
            "Capítulo 2 de 4\n60% · Redoing 2 parts (attempt 1 of 3), please wait…",
            TextoDeProgresso.montar(context, linhas),
        )
    }

    @Test
    fun `conexao lenta acrescenta uma segunda linha na fase normal`() {
        val progresso = ProgressoEstruturado(pct = 18, etaSegundos = 168, falhas = 0, lenta = true)
        assertEquals(
            "18% · about 2 min left\nSlow connection: the conversion will take longer.",
            TextoDeProgresso.frase(context, progresso),
        )
    }

    @Test
    fun `conexao lenta tambem aparece nas rodadas de retry`() {
        val progresso = ProgressoEstruturado(
            pct = 40, etaSegundos = null, falhas = 0, refazendo = 2, rodada = 3, rodadas = 3, lenta = true,
        )
        assertEquals(
            "40% · Redoing 2 parts (attempt 3 of 3), please wait…\nSlow connection: the conversion will take longer.",
            TextoDeProgresso.frase(context, progresso),
        )
    }

    @Test
    fun `sem conexao lenta a frase segue em uma linha`() {
        val progresso = ProgressoEstruturado(pct = 18, etaSegundos = 168, falhas = 0, lenta = false)
        assertFalse(TextoDeProgresso.frase(context, progresso).contains("\n"))
    }
}
