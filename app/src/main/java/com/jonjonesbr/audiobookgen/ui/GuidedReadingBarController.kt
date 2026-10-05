package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.ReadingStartDetector
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.data.RecentReadingsStore
import android.text.Layout
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

private const val HIGHLIGHT_TARGET_RATIO = 0.42f
private const val SCROLL_TRIGGER_THRESHOLD_PX = 6f
private const val SCROLL_STEP_RATIO = 0.18f
// Distância (em itens) a partir da qual o salto para o parágrafo/página alvo é instantâneo
// em vez de animado — acima disso, smoothScrollToPosition percorreria visualmente dezenas/
// centenas de itens (reabrir o app com a guiada longe do início, ou escolher um capítulo
// distante), parecendo o leitor "procurando" o texto por um bom tempo.
private const val SALTO_DISTANTE_LIMIAR = 3

class GuidedReadingBarController(
    private val activity: AppCompatActivity,
    val guidedPlayerManager: GuidedPlayerManager,
    private val viewModel: ReaderViewModel,
    private val recyclerView: RecyclerView,
    private val views: Views,
    private val callbacks: Callbacks,
) {
    private var dicaVozTentada = false
    private var eventoGuiadaRegistrado = false

    data class Views(
        val layoutGuidedPlayer: View,
        val tvGuidedStatus: TextView,
        val progressGuidedLoading: ProgressBar,
        val btnGuidedVoz: ImageButton,
        val btnGuidedPrev: ImageButton,
        val btnGuidedPlayPause: ImageButton,
        val btnGuidedNext: ImageButton,
        val btnGuidedClose: ImageButton,
        val btnGuidedVelocidade: TextView,
        val tvGuidedSoneca: TextView,
        val tvGuidedPassos: TextView,
    )

    data class Callbacks(
        val abrirSeletorVozes: () -> Unit,
        val mostrarDialogoSleepTimer: () -> Unit,
        val atualizarFabs: () -> Unit,
        val fecharPlayerService: () -> Unit,
        val mostrarMiniPlayer: () -> Unit,
        val esconderMiniPlayer: () -> Unit,
        val obterCaminhoArquivo: () -> String,
        val onLivroConcluido: () -> Unit = {},
    )

    private val lifecycleScope = activity.lifecycleScope

    var scrollManual = false
    var aguardandoAutoScroll = false
    var ultimoIndiceGuided = -1
    var ultimoScrollSyncStandard = -1
    var recenteRegistrado = false

    /** True enquanto o diálogo "Começar de onde?" está aberto e sem escolha (a rotação o descarta). */
    var dialogoInicioPendente = false
        private set

    fun onParagrafoToque(indice: Int) {
        val state = viewModel.uiState.value
        if (state.selecaoInicio == null && !state.modoEdicao && !state.mostrandoAlteracoes) {
            playGuidedFrom(indice)
        } else {
            viewModel.onParagrafoToque(indice)
        }
    }

    fun playGuidedFrom(indice: Int) {
        callbacks.fecharPlayerService()
        scrollManual = false
        guidedPlayerManager.play(indice)
    }

    fun obterIndiceInicioLeitura(): Int {
        val capitulos = viewModel.textoEfetivo()?.capitulos ?: return 0
        return ReadingStartDetector.indiceInicio(capitulos)
    }

    fun mostrarDialogPontoInicioLeituraGuiada() {
        val texto = viewModel.textoEfetivo() ?: run {
            playGuidedFrom(0)
            return
        }
        val capitulos = texto.capitulos
        if (capitulos.isEmpty()) {
            playGuidedFrom(0)
            return
        }

        val opcoes = mutableListOf<String>()
        val indices = mutableListOf<Int>()

        opcoes.add(activity.getString(R.string.guided_start_from_beginning))
        indices.add(0)

        val indiceDetectado = obterIndiceInicioLeitura()
        if (indiceDetectado > 0) {
            opcoes.add(activity.getString(R.string.guided_start_from_detected))
            indices.add(indiceDetectado)
        }

        for (cap in capitulos) {
            opcoes.add(cap.titulo)
            indices.add(cap.indiceParagrafoInicio.coerceAtLeast(0))
        }

        dialogoInicioPendente = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.guided_start_dialog_title)
            .setItems(opcoes.toTypedArray()) { _, which ->
                dialogoInicioPendente = false
                playGuidedFrom(indices[which])
            }
            .setCancelable(false)
            .show()
    }

    fun configurarGuidedPlayerBotoes() {
        views.btnGuidedVoz.setOnClickListener { callbacks.abrirSeletorVozes() }
        views.tvGuidedSoneca.setOnClickListener { callbacks.mostrarDialogoSleepTimer() }
        views.tvGuidedPassos.setOnClickListener {
            GuidedVoiceOptionsDialog.show(activity, guidedPlayerManager) { atualizarRotuloPassos() }
        }
        views.btnGuidedVelocidade.setOnClickListener {
            VelocidadeSliderDialog.show(activity, guidedPlayerManager.playbackSpeedMultiplier) { novaVelocidade ->
                guidedPlayerManager.setSpeedMultiplier(novaVelocidade)
                atualizarRotuloVelocidadeGuided()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_velocidade_guided, novaVelocidade),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        views.btnGuidedPrev.setOnClickListener {
            scrollManual = false
            guidedPlayerManager.previous()
        }
        views.btnGuidedPlayPause.setOnClickListener {
            val state = guidedPlayerManager.state.value
            if (state.isPlaying) {
                guidedPlayerManager.pause()
            } else {
                guidedPlayerManager.resume()
            }
        }
        views.btnGuidedNext.setOnClickListener {
            scrollManual = false
            guidedPlayerManager.next()
        }
        views.btnGuidedClose.setOnClickListener {
            guidedPlayerManager.stop()
        }
    }

    fun observarGuidedPlayer() {
        lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                guidedPlayerManager.state.collect { guidedState ->
                    atualizarGuidedPlayerUI(guidedState)
                }
            }
        }
    }

    /** Motor do livro que tem "passos de síntese" ajustáveis: Supertonic ("onnx") ou Pocket; senão null. */
    private fun motorComPassos(): String? {
        val (voz, motorSalvo) = guidedPlayerManager.vozEMotorDoLivro()
        return com.jonjonesbr.audiobookgen.util.VoiceCatalog.effectiveEngine(voz, motorSalvo)
            .takeIf { it == "onnx" || it == "pocket" }
    }

    /** Chip "⚙ N" na barra, visível só quando a voz atual é Supertonic ou Pocket. */
    private fun atualizarRotuloPassos() {
        val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs(activity)
        val passos = when (motorComPassos()) {
            "onnx" -> prefs.supertonicSteps
            "pocket" -> prefs.pocketPassos
            else -> null
        }
        // Sempre visível: abre "Voz e motor" (timbre, passos, pausa, preparo à frente) para qualquer motor.
        views.tvGuidedPassos.visibility = View.VISIBLE
        views.tvGuidedPassos.text =
            if (passos != null) activity.getString(R.string.guided_steps_chip, passos) else activity.getString(R.string.guided_options_chip)
    }

    private fun mostrarDialogoPassos() {
        val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs(activity)
        val aoAplicar = { _: Int -> atualizarRotuloPassos() }
        when (motorComPassos()) {
            "onnx" -> PassosDialog.show(
                activity, R.string.settings_supertonic_steps_title, R.string.guided_steps_desc_supertonic,
                com.jonjonesbr.audiobookgen.data.AppPrefs.SUPERTONIC_STEPS_MIN,
                com.jonjonesbr.audiobookgen.data.AppPrefs.SUPERTONIC_STEPS_MAX,
                prefs.supertonicSteps
            ) { prefs.supertonicSteps = it; aoAplicar(it) }
            "pocket" -> PassosDialog.show(
                activity, R.string.settings_pocket_steps_title, R.string.settings_pocket_steps_desc,
                com.jonjonesbr.audiobookgen.data.AppPrefs.POCKET_PASSOS_MIN,
                com.jonjonesbr.audiobookgen.data.AppPrefs.POCKET_PASSOS_MAX,
                prefs.pocketPassos
            ) { prefs.pocketPassos = it; aoAplicar(it) }
        }
    }

    private fun atualizarRotuloVelocidadeGuided() {
        val mult = guidedPlayerManager.playbackSpeedMultiplier
        val rotuloVelocidade = if (mult == mult.toInt().toFloat()) "${mult.toInt()}.0×" else "${mult}×"
        views.btnGuidedVelocidade.text = rotuloVelocidade
        views.btnGuidedVelocidade.contentDescription = activity.getString(R.string.cd_guided_speed, rotuloVelocidade)
    }

    private fun atualizarGuidedPlayerUI(guidedState: GuidedState) {
        if (guidedState.index == -1) {
            views.layoutGuidedPlayer.visibility = View.GONE
            callbacks.mostrarMiniPlayer()
            viewModel.setParagrafoSyncAtual(-1)
            viewModel.retomarSync()
            ultimoIndiceGuided = -1
            ultimoScrollSyncStandard = -1
            callbacks.atualizarFabs()
            if (guidedState.livroConcluido) callbacks.onLivroConcluido()
            return
        }

        val mudouIndice = guidedState.index != ultimoIndiceGuided
        if (mudouIndice) {
            scrollManual = false
            aguardandoAutoScroll = false
            ultimoIndiceGuided = guidedState.index
        }

        viewModel.pausarStandardSync()

        views.layoutGuidedPlayer.visibility = View.VISIBLE
        if (guidedState.isPlaying && !eventoGuiadaRegistrado) {
            eventoGuiadaRegistrado = true
            com.jonjonesbr.audiobookgen.data.AppPrefs(activity).registrarEvento(com.jonjonesbr.audiobookgen.domain.EventoAprendizado.LEITURA_GUIADA_INICIADA.id)
        }
        if (!dicaVozTentada) {
            dicaVozTentada = true
            views.btnGuidedVoz.post { CoachMark.tentar(activity, com.jonjonesbr.audiobookgen.domain.Dica.LEITOR_VOZ_E_MOTOR, views.btnGuidedVoz) }
        }
        callbacks.esconderMiniPlayer()
        atualizarRotuloVelocidadeGuided()
        atualizarRotuloPassos()
        callbacks.atualizarFabs()

        val caminho = callbacks.obterCaminhoArquivo()
        if (!recenteRegistrado && caminho.isNotEmpty()) {
            recenteRegistrado = true
            RecentReadingsStore.record(
                activity, caminho,
                RecentReadingsStore.nomeLimpo(caminho)
            )
        }

        if (!scrollManual && guidedState.index >= 0) {
            manterDestaqueVisivel(guidedState.index, guidedState.progressPct)
        }

        val texto = viewModel.textoEfetivo()
        val total = texto?.paragrafos?.size ?: 0
        // Título do capítulo atual (para o usuário não se perder na leitura guiada) — cada
        // parágrafo já carrega seu índice de capítulo (Paragrafo.indiceCapitulo).
        val capituloTitulo = texto?.paragrafos?.getOrNull(guidedState.index)?.indiceCapitulo
            ?.let { texto.capitulos.getOrNull(it)?.titulo }
        views.tvGuidedStatus.text = if (capituloTitulo != null) {
            activity.getString(
                R.string.guided_reading_status_capitulo, capituloTitulo, guidedState.index + 1, total
            )
        } else {
            activity.getString(R.string.guided_reading_status, guidedState.index + 1, total)
        }

        val playIcon = R.drawable.ic_player_play
        val pauseIcon = R.drawable.ic_player_pause
        views.btnGuidedPlayPause.setImageResource(if (guidedState.isPlaying) pauseIcon else playIcon)
        val playPauseDescription = activity.getString(
            if (guidedState.isPlaying) R.string.cd_guided_pause else R.string.cd_guided_play
        )
        views.btnGuidedPlayPause.contentDescription = playPauseDescription
        views.btnGuidedPlayPause.tooltipText = playPauseDescription

        views.progressGuidedLoading.visibility = if (guidedState.isLoading) View.VISIBLE else View.GONE

        viewModel.setParagrafoSyncAtual(guidedState.index)
        viewModel.setProgressPct(guidedState.progressPct)

        guidedState.error?.let { err ->
            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
        }
    }

    private fun manterDestaqueVisivel(index: Int, progressPct: Float) {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val tv = recyclerView.findViewHolderForAdapterPosition(index)?.itemView as? TextView

        // Mesmo caminho pra "view da posição ainda não existe" E "rolagem horizontal
        // (paginada)" — esta não tem scroll fino equivalente ao vertical (baseado na posição Y
        // do texto dentro do parágrafo), só pula pra página do parágrafo atual.
        if (tv == null || lm.orientation == LinearLayoutManager.HORIZONTAL) {
            if (!aguardandoAutoScroll) {
                aguardandoAutoScroll = true
                val primeiroVisivel = lm.findFirstVisibleItemPosition()
                val alvoDistante = primeiroVisivel == RecyclerView.NO_POSITION ||
                    Math.abs(index - primeiroVisivel) > SALTO_DISTANTE_LIMIAR
                if (alvoDistante) {
                    // Alvo longe da posição visível (reabertura do app reconectando à guiada,
                    // ou capítulo escolhido distante do início) — salto instantâneo em vez de
                    // smoothScrollToPosition, que animaria a rolagem por todos os itens no meio.
                    lm.scrollToPositionWithOffset(index, 0)
                    aguardandoAutoScroll = false
                } else {
                    lm.smoothScrollToPosition(recyclerView, null, index)
                }
            }
            return
        }

        aguardandoAutoScroll = false
        val layout = tv.layout
        val textLen = tv.text.length
        val viewport = recyclerView.height
        if (layout != null && textLen > 0 && viewport > 0) {
            rolarParaDestaque(tv, layout, textLen, viewport, progressPct)
        }
    }

    private fun rolarParaDestaque(tv: TextView, layout: Layout, textLen: Int, viewport: Int, progressPct: Float) {
        val offset = (textLen * progressPct).toInt().coerceIn(0, textLen - 1)
        val line = layout.getLineForOffset(offset)
        val highlightY = tv.top + tv.totalPaddingTop + layout.getLineBottom(line)

        val alvo = viewport * HIGHLIGHT_TARGET_RATIO
        val delta = highlightY - alvo
        if (delta > SCROLL_TRIGGER_THRESHOLD_PX) {
            val passo = Math.max(1, (delta * SCROLL_STEP_RATIO).toInt())
            recyclerView.scrollBy(0, passo)
        }
    }
}
