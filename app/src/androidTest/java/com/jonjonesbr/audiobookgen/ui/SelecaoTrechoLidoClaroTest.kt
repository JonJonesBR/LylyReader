package com.jonjonesbr.audiobookgen.ui

import android.graphics.Color
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import com.jonjonesbr.audiobookgen.ui.apoio.garantirPalavraVisivel
import com.jonjonesbr.audiobookgen.ui.apoio.selecaoAtual
import com.jonjonesbr.audiobookgen.ui.apoio.selecionarPalavra
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DURACAO_TRECHO_MS = 20_000
private const val ALFA_OPACO = 255
private const val PALAVRA_INICIAL = "Capítulo"

/**
 * Confere o rb-56 no emulador: no tema Claro, o trecho já lido pela leitura guiada recebe
 * [BackgroundColorSpan] translúcido (alfa < 255), permitindo selecionar texto sobre esse trecho
 * sem esconder o destaque da seleção nativa.
 */
@RunWith(AndroidJUnit4::class)
class SelecaoTrechoLidoClaroTest {

    @get:Rule
    val regras = RegrasDoLeitor()

    @Test
    fun selecaoSobreTrechoLidoNoTemaClaroMantemTranslucidezESelecaoAtiva() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        AppPrefs(contexto).readerTema = TemaLeitura.CLARO.ordinal
        regras.usarVoz(
            VozFalsa(
                context = contexto,
                msPorCaractere = 0.0,
                minMs = DURACAO_TRECHO_MS,
                maxMs = DURACAO_TRECHO_MS
            )
        )
        val scenario = abrirLeitorComGuiada(regras, contexto)

        // Aguarda o span do trecho já lido cobrir a primeira palavra do parágrafo 0.
        esperarAte(scenario, "trecho já lido cobrir '$PALAVRA_INICIAL'") { a ->
            val tv = Gestos.textoDoParagrafo(a, 0)
            val texto = tv.text as? Spanned ?: return@esperarAte false
            val spans = texto.getSpans(0, texto.length, BackgroundColorSpan::class.java)
            spans.any { span ->
                texto.getSpanStart(span) == 0 && texto.getSpanEnd(span) >= PALAVRA_INICIAL.length
            }
        }

        garantirPalavraVisivel(scenario, 0, PALAVRA_INICIAL, "rb56")
        selecionarPalavra(scenario, regras, 0, PALAVRA_INICIAL, "rb56")

        val selecao = checkNotNull(selecaoAtual(scenario, 0)) {
            "Seleção não ficou ativa sobre '$PALAVRA_INICIAL' no trecho já lido"
        }
        assertTrue(
            "Seleção deveria conter '$PALAVRA_INICIAL', veio '${selecao.texto}'",
            selecao.texto.contains(PALAVRA_INICIAL)
        )

        scenario.onActivity { a ->
            val tv = Gestos.textoDoParagrafo(a, 0)
            val texto = tv.text as? Spanned
                ?: error("Texto do parágrafo 0 deveria ser Spanned")
            val spans = texto.getSpans(selecao.inicio, selecao.fim, BackgroundColorSpan::class.java)
            assertTrue(
                "Seleção [${selecao.inicio}, ${selecao.fim}) deveria estar sobre o span do trecho lido",
                spans.isNotEmpty()
            )
            val corTrecho = spans.first().backgroundColor
            val alfaTrecho = Color.alpha(corTrecho)
            assertTrue(
                "Cor do trecho lido no tema Claro deve ser translúcida (0 < alfa < 255), veio $alfaTrecho",
                alfaTrecho in 1 until ALFA_OPACO
            )
            assertTrue(
                "highlightColor da seleção nativa deve ser visível (alfa > 0)",
                Color.alpha(tv.highlightColor) > 0
            )
        }
    }
}
