package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import com.jonjonesbr.audiobookgen.domain.ReadingStartDetector
import com.jonjonesbr.audiobookgen.domain.RegraDaPaginaDoParagrafo
import com.jonjonesbr.audiobookgen.domain.RegraDasPaginasDoLeitor
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.data.RecentReadingsStore
import com.jonjonesbr.audiobookgen.util.rotuloVelocidade
import com.jonjonesbr.audiobookgen.util.valePerguntarPorCapitulo
import android.os.Build
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

// Distância (em itens) a partir da qual o salto para o parágrafo/página alvo é instantâneo
// em vez de animado — acima disso, smoothScrollToPosition percorreria visualmente dezenas/
// centenas de itens (reabrir o app com a guiada longe do início, ou escolher um capítulo
// distante), parecendo o leitor "procurando" o texto por um bom tempo.
private const val SALTO_DISTANTE_LIMIAR = 3

/**
 * Vale anunciar "Livro concluído" ao TalkBack? Só na transição: a emissão anterior ainda estava lendo
 * (índice >= 0) e agora a sessão acabou pelo livro ter terminado. [anterior] null é a primeira emissão depois de
 * a tela voltar, que repete o estado e não deve anunciar de novo. Fechar a guiada à mão também zera o índice,
 * mas sem [GuidedState.livroConcluido].
 */
internal fun anunciaLivroConcluido(anterior: GuidedState?, atual: GuidedState): Boolean =
    anterior != null && anterior.index >= 0 && atual.index == -1 && atual.livroConcluido

class GuidedReadingBarController(
    private val activity: AppCompatActivity,
    val guidedPlayerManager: GuidedPlayerManager,
    private val viewModel: ReaderViewModel,
    private val recyclerView: RecyclerViewParagrafos,
    private val views: Views,
    private val callbacks: Callbacks,
) {
    private val dialogos = DialogosAbertos(activity)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)
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
        /** A fila "Ler tudo" passou a narrar outro livro: esta tela segue para ele (ver
         * ReaderActivity.abrirLeitorDoLivroDaFila). */
        val aoMudarDeLivroNaFila: () -> Unit = {},
        /** "Pré-carregar capítulo" das opções do player. */
        val prepararCapitulo: () -> Unit = {},
        /** A leitura guiada foi encerrada; recebe o último parágrafo que ela leu (para retomar dali). */
        val aoEncerrarGuiada: (Int) -> Unit = {},
    )

    private val lifecycleScope = activity.lifecycleScope

    /**
     * Modo manual: quem lê rolou o texto (dedo, TalkBack, teclado ou roda), então o acompanhamento não puxa a
     * rolagem de volta a cada parágrafo. Só muda por [ligarRolagemManual] e [desligarRolagemManual].
     */
    var scrollManual = false
        private set
    var aguardandoAutoScroll = false
    var ultimoIndiceGuided = -1
    /**
     * Parágrafo em que o acompanhamento já avaliou a rolagem com a view pronta (rb-65); enquanto a guiada seguir nele,
     * a lista só desce. -1 libera uma subida.
     */
    private var indiceAcompanhado = -1
    private var ultimoEstadoUi: GuidedState? = null
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

    /** Quem lê rolou (dedo, TalkBack, teclado ou roda do mouse): a barra para de puxar a rolagem até ele pedir. */
    fun ligarRolagemManual() {
        if (scrollManual) return
        scrollManual = true
        callbacks.atualizarFabs()
    }

    /** Volta a acompanhar a narração: pedido explícito (chip, anterior/próximo, tocar num parágrafo) ou fim. */
    fun desligarRolagemManual() {
        if (!scrollManual) return
        scrollManual = false
        callbacks.atualizarFabs()
    }

    /** Chip "Voltar à narração": sai do modo manual e salta para o parágrafo que está sendo narrado. */
    fun voltarAoAcompanhamento() {
        desligarRolagemManual()
        val indice = guidedPlayerManager.state.value.index
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (indice < 0) return
        // O chip some logo depois deste toque: sem o anúncio, o foco do TalkBack se perderia sem aviso.
        recyclerView.announceForAccessibility(activity.getString(R.string.cd_guided_voltar_narracao))
        // Salto direto, como a navegação por capítulos. Na vertical, o parágrafo fica na altura da linha de
        // destaque; o acompanhamento fino retoma com o próximo progresso. No modo páginas, o topo vai para a primeira
        // linha inteira depois do layout (ver PaginacaoDoLeitor), e o próximo progresso vira a página até a narrada.
        val margem = if (lm.orientation == LinearLayoutManager.VERTICAL && recyclerView.paginacao?.ativa != true) {
            (recyclerView.height * RegraDaPaginaDoParagrafo.PROPORCAO_ALVO).toInt()
        } else {
            0
        }
        // O salto só vale no próximo layout. Até lá, um progresso que não ache a view do alvo refaz o salto
        // (manterDestaqueVisivel). A flag fica ligada até o próximo parágrafo, o fim de uma rolagem ou, na vertical,
        // o primeiro destaque com a view pronta.
        aguardandoAutoScroll = true
        lm.scrollToPositionWithOffset(indice, margem)
    }

    fun playGuidedFrom(indice: Int) {
        callbacks.fecharPlayerService()
        desligarRolagemManual()
        // rb-65: o toque ou o capítulo pode cair no parágrafo que já está sendo narrado; então a lista pode subir até
        // ele.
        indiceAcompanhado = -1
        // rb-65 (c): tirar o foco do parágrafo tocado. No P+, em modo de toque, View.clearFocus não reatribui o foco
        // (refocus = sAlwaysAssignFocus || !isInTouchMode, e o targetSdk é 36). No 26/27 ele chamaria
        // rootViewRequestFocus, e o foco iria para outro parágrafo (rb-59). Fora do modo de toque (teclado, D-pad), o
        // foco é de quem navega. Com seleção aberta, o foco fica, para não derrubar o menu de texto.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && recyclerView.isInTouchMode) {
            (recyclerView.findFocus() as? TextView)?.takeIf { !it.hasSelection() }?.clearFocus()
        }
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
        // Um capítulo só (ou nenhum): não há o que escolher, então vai direto. Com um capítulo, ainda pula capa
        // e sumário como o início detectado faria na pergunta; sem capítulo, começa do parágrafo 0.
        if (!valePerguntarPorCapitulo(capitulos.size)) {
            playGuidedFrom(if (capitulos.size == 1) obterIndiceInicioLeitura() else 0)
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
            opcoes.add(NavegacaoCapitulos.tituloLimpo(cap.titulo))
            indices.add(cap.indiceParagrafoInicio.coerceAtLeast(0))
        }

        dialogoInicioPendente = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.guided_start_dialog_title)
            .setItems(opcoes.toTypedArray()) { _, which ->
                dialogoInicioPendente = false
                playGuidedFrom(indices[which])
            }
            // Cancelar (botão, voltar ou toque fora) deixa a leitura parada. Não usa onDismiss: ele também
            // dispara depois de escolher um item.
            .setNegativeButton(android.R.string.cancel) { _, _ -> dialogoInicioPendente = false }
            .setOnCancelListener { dialogoInicioPendente = false }
            .setCancelable(true)
            .exibir()
    }

    fun configurarGuidedPlayerBotoes() {
        views.btnGuidedVoz.setOnClickListener { callbacks.abrirSeletorVozes() }
        views.tvGuidedSoneca.setOnClickListener { callbacks.mostrarDialogoSleepTimer() }
        views.tvGuidedPassos.setOnClickListener {
            GuidedVoiceOptionsDialog.show(activity, guidedPlayerManager, callbacks.prepararCapitulo) {
                atualizarRotuloPassos()
            }
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
            desligarRolagemManual()
            guidedPlayerManager.previous()
        }
        views.btnGuidedPlayPause.setOnClickListener {
            val state = guidedPlayerManager.state.value
            // Preparando a narração (antes do primeiro som): não há o que pausar nem retomar. Quem pausou durante o
            // carregamento segue podendo retomar (estaPausado).
            if (state.isLoading && !state.isPlaying && !guidedPlayerManager.estaPausado) return@setOnClickListener
            if (state.isPlaying) {
                guidedPlayerManager.pause()
            } else {
                guidedPlayerManager.resume()
            }
        }
        views.btnGuidedNext.setOnClickListener {
            desligarRolagemManual()
            guidedPlayerManager.next()
        }
        views.btnGuidedClose.setOnClickListener {
            guidedPlayerManager.stop()
        }
    }

    fun observarGuidedPlayer() {
        lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Ao voltar para a tela, refaz a barra inteira na primeira emissão.
                ultimoEstadoUi = null
                // A tela voltou: uma correção para cima é permitida de novo (rb-65).
                indiceAcompanhado = -1
                guidedPlayerManager.state.collect { guidedState ->
                    // A fila já passou a narrar outro livro (com a tela apagada, isto só roda ao voltar): esta tela
                    // segue para ele. Vem antes do bloco de fim (-1), que também cobre "a fila acabou fora da tela".
                    if (guidedPlayerManager.caminhoArquivo.isNotEmpty() &&
                        guidedPlayerManager.caminhoArquivo != callbacks.obterCaminhoArquivo()
                    ) {
                        callbacks.aoMudarDeLivroNaFila()
                        return@collect
                    }
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
        // O chip abre "Voz e motor" em qualquer motor: a descrição (leitor de tela) e a dica seguem o rótulo.
        val (rotulo, descricao) = if (passos != null) {
            activity.getString(R.string.guided_steps_chip, passos) to
                activity.getString(R.string.cd_guided_voz_motor_passos, passos)
        } else {
            activity.getString(R.string.guided_options_chip) to activity.getString(R.string.cd_guided_voz_motor)
        }
        views.tvGuidedPassos.text = rotulo
        views.tvGuidedPassos.contentDescription = descricao
        views.tvGuidedPassos.tooltipText = descricao
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

    /** Voz ou velocidade do livro mudaram fora da barra (menu ⋮ › Voz e velocidade deste livro). */
    fun atualizarRotulosDoPerfil() {
        atualizarRotuloVelocidadeGuided()
        atualizarRotuloPassos()
    }

    private fun atualizarRotuloVelocidadeGuided() {
        val mult = guidedPlayerManager.playbackSpeedMultiplier
        val rotulo = rotuloVelocidade(mult)
        views.btnGuidedVelocidade.text = rotulo
        views.btnGuidedVelocidade.contentDescription = activity.getString(R.string.cd_guided_speed, rotulo)
    }

    private fun atualizarGuidedPlayerUI(guidedState: GuidedState) {
        val estadoAnterior = ultimoEstadoUi
        // O progresso do parágrafo chega a cada ~50 ms: nesses ticks só move o destaque e a rolagem. Rótulos,
        // botões e preferências só se refazem quando parágrafo, play/pausa, carregamento ou erro mudam.
        val soProgresso = guidedState.index >= 0 && guidedState.error == null &&
            ultimoEstadoUi?.copy(progressPct = guidedState.progressPct) == guidedState
        ultimoEstadoUi = guidedState
        if (soProgresso) {
            // Com a seleção de texto em uso (dedo na lista ou menu aberto), o destaque não rola o texto (rb-38).
            if (!scrollManual && !recyclerView.textoEmUso) {
                manterDestaqueVisivel(guidedState.index, guidedState.progressPct)
            }
            viewModel.setProgressPct(guidedState.progressPct)
            return
        }
        if (guidedState.index == -1) {
            // Anuncia pelo recyclerView, que continua na tela (o layoutGuidedPlayer some logo abaixo). A soneca
            // disparada não ganha anúncio próprio: o Toast de ReaderSonecaController já é lido pelo TalkBack.
            if (anunciaLivroConcluido(estadoAnterior, guidedState)) {
                recyclerView.announceForAccessibility(activity.getString(R.string.guided_livro_concluido))
            }
            if (ultimoIndiceGuided >= 0) callbacks.aoEncerrarGuiada(ultimoIndiceGuided)
            views.layoutGuidedPlayer.visibility = View.GONE
            callbacks.mostrarMiniPlayer()
            viewModel.setParagrafoSyncAtual(-1)
            viewModel.retomarSync()
            ultimoIndiceGuided = -1
            indiceAcompanhado = -1
            ultimoScrollSyncStandard = -1
            // Guiada encerrada: a próxima começa acompanhando de novo.
            desligarRolagemManual()
            callbacks.atualizarFabs()
            return
        }

        // play() recomeçou o parágrafo (troca de voz, trecho que falhou, índice perdido): no mesmo índice, uma subida
        // volta a ser permitida (rb-65). Dentro de um parágrafo, isLoading só fica true no começo de um play().
        if (guidedState.isLoading && estadoAnterior?.isLoading != true) indiceAcompanhado = -1
        val mudouIndice = guidedState.index != ultimoIndiceGuided
        // Não desliga o modo manual aqui: quem rolou para adiante não é puxado de volta a cada parágrafo.
        if (mudouIndice) {
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

        if (!scrollManual && !recyclerView.textoEmUso && guidedState.index >= 0) {
            manterDestaqueVisivel(guidedState.index, guidedState.progressPct)
        }

        val texto = viewModel.textoEfetivo()
        val total = texto?.paragrafos?.size ?: 0
        // Título do capítulo atual (para o usuário não se perder na leitura guiada) — cada
        // parágrafo já carrega seu índice de capítulo (Paragrafo.indiceCapitulo).
        val capituloTitulo = texto?.paragrafos?.getOrNull(guidedState.index)?.indiceCapitulo
            ?.let { texto.capitulos.getOrNull(it)?.titulo }
            ?.let { NavegacaoCapitulos.tituloLimpo(it) }
        // Antes do primeiro som o status do parágrafo não diz nada útil: avisa que a narração está sendo preparada.
        // Quem pausou durante o carregamento (estaPausado) continua vendo o parágrafo.
        val preparandoNarracao = guidedState.isLoading && !guidedState.isPlaying && !guidedPlayerManager.estaPausado
        views.tvGuidedStatus.text = when {
            preparandoNarracao -> activity.getString(R.string.guided_preparando_narracao)
            capituloTitulo != null -> activity.getString(
                R.string.guided_reading_status_capitulo, capituloTitulo, guidedState.index + 1, total
            )
            else -> activity.getString(R.string.guided_reading_status, guidedState.index + 1, total)
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
        // rb-65: no mesmo parágrafo já acompanhado a lista só desce. Subir (rb-62) só no primeiro acompanhamento depois
        // de o parágrafo mudar (troca de voz, ⏮, toque, capítulo): play() zera indiceAcompanhado.
        val podeSubir = index != indiceAcompanhado
        // Modo páginas (rb-26b): a página vira com a narração, em vez do cálculo da rolagem vertical.
        val paginacao = recyclerView.paginacao?.takeIf { it.ativa }

        // Mesmo caminho pra "view da posição ainda não existe" E "rolagem horizontal (paginada)". Na horizontal, com
        // a página do parágrafo parada na tela, o acompanhamento rola dentro dela (rb-26a), como o vertical. Durante o
        // snap, ou sem essa página, só pula pra página do parágrafo atual.
        if (tv == null || lm.orientation == LinearLayoutManager.HORIZONTAL) {
            val pagina = recyclerView.paginaParadaNaTela()
            if (pagina != null && recyclerView.getChildAdapterPosition(pagina) == index) {
                aguardandoAutoScroll = false
                if (pagina.acompanharDestaque(progressPct, podeSubir)) indiceAcompanhado = index
            } else if (!aguardandoAutoScroll) {
                aguardandoAutoScroll = true
                val primeiroVisivel = lm.findFirstVisibleItemPosition()
                val alvoDistante = primeiroVisivel == RecyclerView.NO_POSITION ||
                    Math.abs(index - primeiroVisivel) > SALTO_DISTANTE_LIMIAR
                val vertical = lm.orientation == LinearLayoutManager.VERTICAL
                if (vertical || alvoDistante) {
                    // Salto instantâneo, sem smoothScrollToPosition (que animaria por todos os itens do meio). Na
                    // vertical, a 1ª linha do parágrafo vai para os 42% (a mesma conta de voltarAoAcompanhamento).
                    // O SNAP_TO_END passava do início, e o acompanhamento voltava a subir (rb-65). Na horizontal
                    // distante, offset 0. No modo páginas, offset 0 também: o topo é alinhado depois do layout.
                    lm.scrollToPositionWithOffset(
                        index,
                        if (vertical && paginacao == null) {
                            (recyclerView.height * RegraDaPaginaDoParagrafo.PROPORCAO_ALVO).toInt()
                        } else {
                            0
                        },
                    )
                } else {
                    lm.smoothScrollToPosition(recyclerView, null, index)
                }
                // Alvo distante (reabertura do app reconectando à guiada, ou capítulo escolhido longe do início): a
                // flag cai e a nova tentativa vem a cada tique. Perto, a flag fica ligada até a view aparecer
                // (vertical) ou a animação acabar (horizontal).
                aguardandoAutoScroll = !alvoDistante
            }
            return
        }

        aguardandoAutoScroll = false
        if (paginacao != null) {
            acompanharNaPagina(paginacao, tv, index, progressPct, podeSubir)
        } else {
            rolarParaDestaque(tv, index, progressPct, podeSubir)
        }
    }

    /**
     * Modo páginas (rb-26b): a página vira até a linha narrada ficar no topo, quando ela termina escondida pelo
     * corte. Sobe só com [podeSubir] (a histerese do rb-65). Scroll por código: não liga o modo manual, como
     * rolarParaDestaque.
     */
    private fun acompanharNaPagina(
        paginacao: PaginacaoDoLeitor,
        tv: TextView,
        index: Int,
        progressPct: Float,
        podeSubir: Boolean,
    ) {
        val linha = linhaDoProgresso(tv, progressPct)
        val passo = linha?.let {
            val corte = paginacao.corte()
            RegraDasPaginasDoLeitor.passoDaGuiada(it.topo, it.base, recyclerView.paddingTop, corte, podeSubir)
        } ?: 0
        // Animação de rolagem ainda em curso: espera o fim, como em rolarParaDestaque.
        val emAnimacao = passo != 0 && recyclerView.scrollState == RecyclerView.SCROLL_STATE_SETTLING
        if (emAnimacao) return
        if (passo != 0) recyclerView.scrollBy(0, passo)
        if (linha != null) indiceAcompanhado = index
    }

    /**
     * Rola a lista (vertical) para a linha em leitura. Marca o parágrafo em [indiceAcompanhado] quando avaliou a regra.
     * Enquanto a animação de rolagem (SETTLING) está em curso, não marca: o próximo tique tenta de novo.
     */
    private fun rolarParaDestaque(tv: TextView, index: Int, progressPct: Float, podeSubir: Boolean) {
        val layout = tv.layout
        val textLen = tv.text.length
        if (layout == null || textLen == 0 || recyclerView.height <= 0) return
        val offset = (textLen * progressPct).toInt().coerceIn(0, textLen - 1)
        val line = layout.getLineForOffset(offset)
        val highlightY = tv.top + tv.totalPaddingTop + layout.getLineBottom(line)

        // Mesmo parágrafo: só desce (rb-65). Sobe só no primeiro acompanhamento de um parágrafo novo (rb-62).
        // Linha acima do topo (o ⏮ volta a um parágrafo que começa fora da tela) ou abaixo da área salta direto para
        // o destaque; dentro da área, rola em passos.
        val passo = RegraDaPaginaDoParagrafo.passoComHisterese(highlightY, recyclerView.height, podeSubir)
        // Animação de rolagem (smoothScroll) ainda em curso: espera o fim em vez de brigar com ela. O próximo
        // progresso recalcula o passo. A rolagem do dedo não chega aqui (só roda sem modo manual).
        val emAnimacao = passo != 0 && recyclerView.scrollState == RecyclerView.SCROLL_STATE_SETTLING
        if (emAnimacao) return
        // scrollBy por código não liga o modo manual: é só o acompanhamento.
        if (passo != 0) recyclerView.scrollBy(0, passo)
        indiceAcompanhado = index
    }
}
