package com.jonjonesbr.audiobookgen.ui

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
     * C17 (rb-62): com a guiada no parágrafo 2 e o parágrafo 1 mais alto que a tela, o ⏮ volta ao 1 e a lista sobe até
     * a linha dele. Antes, com a primeira linha do 1 acima do topo, a lista não se movia até a próxima narração.
     */
    @Test
    fun c17_anteriorNaGuiadaSobeAteALinhaDoParagrafoAnterior() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        regras.usarVoz(VozFalsa.muitoLonga(contexto))
        val textos = LivroNoAparelho.PARAGRAFOS.toMutableList().apply { this[1] = LivroNoAparelho.PARAGRAFO_LONGO }

        val scenario = abrirLeitorComGuiada(regras, contexto, textos = textos, posicaoInicial = 2)
        esperarAte(scenario, "leitura guiada no parágrafo 2 com a lista parada") { a ->
            GuidedPlaybackBridge.manager?.state?.value?.index == 2 &&
                lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
        }
        // Parágrafo 2 a 42% da lista (a altura de destaque do acompanhamento). Com o parágrafo 1 acima e mais alto
        // que a tela, a primeira linha dele sai pelo topo, como depois de a leitura ter vindo do 1 para o 2.
        scenario.onActivity { a ->
            val rv = lista(a)
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(2, (rv.height * 0.42f).toInt())
        }
        // Pré-condição: o parágrafo 1 está na lista, com a base da primeira linha acima do topo.
        esperarAte(scenario, "parágrafo 1 na lista com a primeira linha acima do topo") { a ->
            baseDaPrimeiraLinha(a, 1)?.let { it < 0 } ?: false
        }

        val (x, y) = Gestos.centroDaView(scenario, R.id.btnGuidedPrev, "botão anterior da leitura guiada")
        regras.fecharDialogosDoSistema()
        Gestos.toqueCurto(x.toFloat(), y.toFloat())
        esperarAte(scenario, "leitura guiada voltou ao parágrafo 1") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.index == 1
        }
        Thread.sleep(500)

        // Medido na tela: a base da primeira linha do parágrafo 1 tem de estar entre o topo da lista e o topo da barra.
        var base = -1
        var topoDaLista = 0
        var topoDaBarra = 0
        var indice = -1
        scenario.onActivity { a ->
            val posLista = IntArray(2).also { lista(a).getLocationOnScreen(it) }
            val posBarra = IntArray(2).also { a.findViewById<View>(R.id.layoutGuidedPlayer).getLocationOnScreen(it) }
            topoDaLista = posLista[1]
            topoDaBarra = posBarra[1]
            base = baseDaPrimeiraLinha(a, 1)?.let { it + posLista[1] } ?: -1
            indice = GuidedPlaybackBridge.manager?.state?.value?.index ?: -1
        }
        Capturas.anotar(
            "c17-anterior",
            "index=$indice base=$base topoDaLista=$topoDaLista topoDaBarra=$topoDaBarra"
        )
        Capturas.capturar("33_c17_anterior_sobe_ate_a_linha")
        assertTrue(
            "a primeira linha do parágrafo 1 deveria estar entre o topo da lista ($topoDaLista) e a barra " +
                "($topoDaBarra), mas a base está em $base",
            indice == 1 && base > topoDaLista && base <= topoDaBarra
        )
    }

    /**
     * C20 (rb-41): a sessão de mídia da notificação da guiada informa a duração e a posição do trecho que está
     * tocando. A sessão é achada pela notificação do próprio app (EXTRA_MEDIA_SESSION), como a barra do sistema
     * faz. Com a voz muito longa (240 s por trecho): a duração tem de ser ~240 s, a posição estimada tem de andar
     * enquanto toca e ficar parada depois da pausa (pelo botão real).
     */
    @Test
    fun c20_notificacaoDaGuiadaInformaDuracaoEPosicaoDoTrecho() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val contexto = instrumentacao.targetContext
        regras.usarVoz(VozFalsa.muitoLonga(contexto))

        val scenario = abrirLeitorComGuiada(regras, contexto)
        esperarAte(scenario, "leitura guiada tocando o parágrafo 0") { _ ->
            GuidedPlaybackBridge.manager?.state?.value?.let { it.isPlaying && it.index == 0 } == true
        }
        regras.fecharDialogosDoSistema()
        val controlador = esperarControladorDaNotificacao(instrumentacao, contexto)

        val tocando = esperarLeitura(instrumentacao, controlador, "a sessão tocando, com duração") { l ->
            l.tocando && l.duracaoMs > 0 && l.velocidade > 0f && l.atualizadaEmMs > 0
        }
        Capturas.anotar("c20-tocando", "duracaoMs=${tocando.duracaoMs} $tocando")
        assertTrue(
            "A notificação deveria informar a duração do trecho (~${DURACAO_DO_TRECHO_MS / 1000} s), " +
                "mas a sessão mostrou ${tocando.duracaoMs} ms. Leitura: $tocando",
            abs(tocando.duracaoMs - DURACAO_DO_TRECHO_MS) <= TOLERANCIA_DURACAO_MS
        )

        val antes = lerSessao(instrumentacao, controlador)
        Thread.sleep(INTERVALO_DE_POSICAO_MS)
        val depois = lerSessao(instrumentacao, controlador)
        val andou = depois.posicaoEstimadaMs - antes.posicaoEstimadaMs
        val esperado = (INTERVALO_DE_POSICAO_MS * tocando.velocidade).toLong()
        Capturas.anotar("c20-andou", "andouMs=$andou esperadoMs=$esperado antes=$antes depois=$depois")
        assertTrue(
            "Com a guiada tocando, a posição da notificação deveria andar ~$esperado ms em " +
                "$INTERVALO_DE_POSICAO_MS ms, mas andou $andou ms. Antes: $antes. Depois: $depois",
            andou >= esperado / 2
        )

        val (x, y) = Gestos.centroDaView(scenario, R.id.btnGuidedPlayPause, "botão de tocar/pausar da leitura guiada")
        regras.fecharDialogosDoSistema()
        UiDevice.getInstance(instrumentacao).click(x, y)
        val pausada = esperarLeitura(instrumentacao, controlador, "a sessão da notificação pausada") { l ->
            l.estado?.state == PlaybackState.STATE_PAUSED && l.velocidade == 0f
        }
        val paradaAntes = lerSessao(instrumentacao, controlador)
        Thread.sleep(INTERVALO_DE_POSICAO_MS)
        val paradaDepois = lerSessao(instrumentacao, controlador)
        Capturas.anotar("c20-pausada", "antes=$paradaAntes depois=$paradaDepois")
        assertTrue(
            "Pausada pelo botão, a posição da notificação deveria ficar parada, mas foi de " +
                "${paradaAntes.posicaoEstimadaMs} para ${paradaDepois.posicaoEstimadaMs} ms. " +
                "Pausada: $pausada. Depois: $paradaDepois",
            abs(paradaDepois.posicaoEstimadaMs - paradaAntes.posicaoEstimadaMs) <= TOLERANCIA_PAUSA_MS
        )

        // Registro visual: a aba de notificações mostra a barra de mídia. Fecha só se a aba abriu.
        val dispositivo = UiDevice.getInstance(instrumentacao)
        val abriuAba = dispositivo.openNotification()
        try {
            Thread.sleep(ESPERA_GAVETA_MS)
            Capturas.capturar("51_c20_notificacao_guiada_pausada")
        } finally {
            if (abriuAba) dispositivo.pressBack()
        }
    }

    /**
     * Acha a sessão de mídia pela notificação do app (o extra EXTRA_MEDIA_SESSION do MediaStyle) e cria o
     * controlador na thread principal. Falha dizendo o que se viu se nenhuma notificação trouxer a sessão.
     */
    private fun esperarControladorDaNotificacao(instrumentacao: Instrumentation, contexto: Context): MediaController {
        val fim = System.currentTimeMillis() + TEMPO_NOTIFICACAO_MS
        var token = tokenDaNotificacaoDeMidia(contexto)
        while (token == null && System.currentTimeMillis() < fim) {
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
            token = tokenDaNotificacaoDeMidia(contexto)
        }
        // throw, e não fail(): Assert.fail devolve Unit no Kotlin, e o elvis não compilaria com ele.
        val sessao: MediaSession.Token = token ?: throw AssertionError(
            "Nenhuma notificação do app trouxe a sessão de mídia (EXTRA_MEDIA_SESSION) em " +
                "${TEMPO_NOTIFICACAO_MS / 1000} s. Notificações ativas: " +
                contexto.getSystemService(NotificationManager::class.java).activeNotifications.size
        )
        var controlador: MediaController? = null
        instrumentacao.runOnMainSync { controlador = MediaController(contexto, sessao) }
        return checkNotNull(controlador) { "MediaController não criado na thread principal" }
    }

    /** Token da sessão de mídia que vem nos extras de alguma notificação ativa do app; nulo se não há. */
    @Suppress("DEPRECATION")
    private fun tokenDaNotificacaoDeMidia(contexto: Context): MediaSession.Token? =
        contexto.getSystemService(NotificationManager::class.java).activeNotifications.asSequence()
            .mapNotNull { it.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) }
            .firstOrNull()

    /** Lê a sessão na thread principal (o controlador é do sistema, e o instante é o da leitura). */
    private fun lerSessao(instrumentacao: Instrumentation, controlador: MediaController): LeituraDaSessao {
        var leitura: LeituraDaSessao? = null
        instrumentacao.runOnMainSync {
            leitura = LeituraDaSessao(
                estado = controlador.playbackState,
                duracaoMs = controlador.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L,
                agoraMs = SystemClock.elapsedRealtime(),
            )
        }
        return checkNotNull(leitura) { "Sessão não lida na thread principal" }
    }

    /**
     * Relê a sessão até [condicao] valer ou estourar [TEMPO_SESSAO_MS]. Falha com a última leitura, para a
     * mensagem dizer o que a sessão mostrava.
     */
    private fun esperarLeitura(
        instrumentacao: Instrumentation,
        controlador: MediaController,
        descricao: String,
        condicao: (LeituraDaSessao) -> Boolean,
    ): LeituraDaSessao {
        val fim = System.currentTimeMillis() + TEMPO_SESSAO_MS
        var ultima = lerSessao(instrumentacao, controlador)
        while (!condicao(ultima) && System.currentTimeMillis() < fim) {
            Thread.sleep(INTERVALO_DE_LEITURA_MS)
            ultima = lerSessao(instrumentacao, controlador)
        }
        if (!condicao(ultima)) fail("Tempo esgotado esperando $descricao. Última leitura: $ultima")
        return ultima
    }

    /** Base da primeira linha do parágrafo [indice], relativa ao topo da lista; nula se ele não está na lista. */
    private fun baseDaPrimeiraLinha(a: ReaderActivity, indice: Int): Int? {
        val tv = lista(a).findViewHolderForAdapterPosition(indice)?.itemView as? TextView ?: return null
        val layout = tv.layout ?: return null
        return tv.top + tv.totalPaddingTop + layout.getLineBottom(0)
    }

    private fun lista(a: ReaderActivity): RecyclerView = a.findViewById(R.id.recyclerViewParagrafos)

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

        /** Duração do trecho com a voz muito longa (240 s) e a folga aceita para o WAV e o player (rb-41, C20). */
        const val DURACAO_DO_TRECHO_MS = 240_000L
        const val TOLERANCIA_DURACAO_MS = 5_000L

        /** Intervalo entre duas leituras da posição estimada, e a folga aceita com a guiada pausada. */
        const val INTERVALO_DE_POSICAO_MS = 1_500L
        const val TOLERANCIA_PAUSA_MS = 100L

        /** Tempo para a notificação com a sessão aparecer, e para a sessão chegar ao estado pedido. */
        const val TEMPO_NOTIFICACAO_MS = 15_000L
        const val TEMPO_SESSAO_MS = 10_000L
        const val INTERVALO_DE_LEITURA_MS = 100L

        /** Espera, com a aba de notificações aberta, antes da captura de registro. */
        const val ESPERA_GAVETA_MS = 800L
    }
}

/**
 * Uma leitura da sessão de mídia da guiada: o estado (PlaybackState), a duração do trecho
 * (METADATA_KEY_DURATION) e o instante (elapsedRealtime) em que foi lida.
 */
private class LeituraDaSessao(val estado: PlaybackState?, val duracaoMs: Long, val agoraMs: Long) {

    val tocando: Boolean get() = estado?.state == PlaybackState.STATE_PLAYING

    val velocidade: Float get() = estado?.playbackSpeed ?: 0f

    /** Instante da última posição informada pela sessão (0 se ela não informou nenhuma). */
    val atualizadaEmMs: Long get() = estado?.lastPositionUpdateTime ?: 0L

    /**
     * Posição agora, como a barra do sistema calcula: a última posição mais o tempo decorrido na velocidade dela.
     * Com velocidade 0 (pausada), é a própria posição.
     */
    val posicaoEstimadaMs: Long
        get() = estado?.let { it.position + ((agoraMs - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() } ?: -1L

    override fun toString(): String =
        "estado=${estado?.state} posicao=${estado?.position} velocidade=${estado?.playbackSpeed} " +
            "atualizadaEm=${estado?.lastPositionUpdateTime} duracaoMs=$duracaoMs"
}
