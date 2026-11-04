package com.jonjonesbr.audiobookgen.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.ui.apoio.Capturas
import com.jonjonesbr.audiobookgen.ui.apoio.Gestos
import com.jonjonesbr.audiobookgen.ui.apoio.LivroNoAparelho
import com.jonjonesbr.audiobookgen.ui.apoio.RegrasDoLeitor
import com.jonjonesbr.audiobookgen.ui.apoio.VozFalsa
import com.jonjonesbr.audiobookgen.ui.apoio.abrirLeitorComGuiada
import com.jonjonesbr.audiobookgen.ui.apoio.esperarAte
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leitura guiada no emulador (rb-46 U2): a ReaderActivity abre já tocando ("Continuar lendo", sem diálogo), com
 * uma voz falsa no lugar do motor. O áudio é silêncio: confere-se o estado da guiada (índice, tocando, pausa).
 */
@RunWith(AndroidJUnit4::class)
class GuiadaNoAparelhoTest {

    // Sem Python, com voz falsa e preferências limpas: ver RegrasDoLeitor.
    @get:Rule
    val regras = RegrasDoLeitor()

    /**
     * (a) A guiada toca o parágrafo 0 com a voz longa (30 s) e, com um toque no botão, pausa.
     */
    @Test
    fun guiadaTocaEPausa() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val contexto = instrumentacao.targetContext
        regras.usarVoz(VozFalsa.longa(contexto))

        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
        }
        val antes = GuidedPlaybackBridge.manager?.state?.value
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "guiada-tocando",
            "index=${antes?.index} isPlaying=${antes?.isPlaying} progressPct=${antes?.progressPct}"
        )
        Capturas.capturar("02_guiada_tocando")

        val (x, y) = Gestos.centroDaView(scenario, R.id.btnGuidedPlayPause, "botão de tocar/pausar da leitura guiada")
        regras.fecharDialogosDoSistema()
        UiDevice.getInstance(instrumentacao).click(x, y)

        esperarAte(scenario, "leitura guiada pausada") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.isPlaying == false
        }
        val depois = GuidedPlaybackBridge.manager?.state?.value
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "guiada-pausada",
            "index=${depois?.index} isPlaying=${depois?.isPlaying} progressPct=${depois?.progressPct}"
        )
        Capturas.capturar("03_guiada_pausada")
    }

    /**
     * (b) Com a voz curta (1,5 s por trecho), a guiada passa do parágrafo 0 para o 1 sozinha. Se não passar, a
     * mensagem diz o que a guiada estava fazendo (ver [mensagemDeAvancoFalhou]), para separar falta de áudio de outras
     * falhas.
     */
    @Test
    fun guiadaAvancaParagrafo() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        regras.usarVoz(VozFalsa.curta(contexto))

        val scenario = abrirLeitorComGuiada(regras, contexto)
        // Uma espera só. Com a voz curta, o parágrafo 0 pode acabar antes de o teste começar a olhar (a abertura é
        // lenta), então não se exige vê-lo tocando. A prova de que ele tocou é a voz ter recebido o texto dele
        // (pediuParagrafo0): o prefetch só pede os seguintes, então, se a guiada começasse no 1, esse texto nunca
        // seria pedido. viuParagrafo0Tocando só registra o que o teste viu, para a mensagem de falha.
        var viuParagrafo0Tocando = false
        try {
            esperarAte(scenario, "leitura guiada passando do parágrafo 0 para o 1") { _ ->
                val s = GuidedPlaybackBridge.manager?.state?.value
                if (s != null && s.isPlaying && s.index == 0) viuParagrafo0Tocando = true
                (s?.index ?: -1) >= 1 && pediuParagrafo0(regras.vozFalsa)
            }
        } catch (erro: AssertionError) {
            val mensagem = mensagemDeAvancoFalhou(erro.message, viuParagrafo0Tocando)
            Capturas.anotar("guiada-avanco-falhou", mensagem)
            throw AssertionError(mensagem, erro)
        }
        val estado = GuidedPlaybackBridge.manager?.state?.value
        regras.fecharDialogosDoSistema()
        Capturas.anotar(
            "guiada-avanco",
            "index=${estado?.index} isPlaying=${estado?.isPlaying} progressPct=${estado?.progressPct} " +
                "textosPedidos=${regras.vozFalsa.textosPedidos.size}"
        )
        Capturas.capturar("04_guiada_avancou")
    }

    /**
     * Se algum texto pedido à voz começa pelo parágrafo 0: a guiada mandou sintetizá-lo, e não só pulou para o 1.
     * Lê a lista da voz falsa (thread-safe), então pode ser chamada na condição da espera.
     */
    private fun pediuParagrafo0(voz: VozFalsa): Boolean {
        val inicio = LivroNoAparelho.PARAGRAFOS[0].take(TAMANHO_DO_INICIO_DO_PARAGRAFO_0)
        return voz.textosPedidos.any { it.contains(inicio) }
    }

    /**
     * Mensagem para quando o avanço do parágrafo 0 para o 1 não acontece. Lê o estado da guiada no momento da
     * falha.
     * Só atribui a falta de áudio quando o padrão bate (tocando, sem progresso e sem erro); nos demais casos diz que
     * a guiada não chegou a tocar ou que houve erro, sem culpar o -noaudio.
     */
    private fun mensagemDeAvancoFalhou(motivo: String?, viuParagrafo0Tocando: Boolean): String {
        val s = GuidedPlaybackBridge.manager?.state?.value
        val diagnostico = when {
            s?.error != null -> "a leitura guiada reportou erro: ${s.error}"
            s?.isPlaying == true && s.progressPct == 0f && s.index == 0 ->
                "MediaPlayer não avançou sem saída de áudio (o emulador roda com -noaudio?)"
            s == null || s.index < 0 || s.isLoading || regras.vozFalsa.textosPedidos.isEmpty() ->
                "a guiada não chegou a sintetizar ou tocar o parágrafo 0"
            else -> "a guiada tocou, mas não avançou para o parágrafo 1"
        }
        return "$diagnostico. index=${s?.index} isPlaying=${s?.isPlaying} isLoading=${s?.isLoading} " +
            "progressPct=${s?.progressPct} error=${s?.error} textosPedidos=${regras.vozFalsa.textosPedidos.size} " +
            "viuParagrafo0Tocando=$viuParagrafo0Tocando pediuParagrafo0=${pediuParagrafo0(regras.vozFalsa)}. " +
            "Motivo: $motivo"
    }

    private companion object {
        /** Começo do parágrafo 0 que tem de aparecer num texto pedido à voz (basta para achar o trecho). */
        const val TAMANHO_DO_INICIO_DO_PARAGRAFO_0 = 20
    }
}
