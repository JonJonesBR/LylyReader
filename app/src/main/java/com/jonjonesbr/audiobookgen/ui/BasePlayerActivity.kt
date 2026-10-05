package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.PlayerState
import android.os.Bundle
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Activity base que gerencia o mini-player colapsível.
 * Todas as Activities do app devem estender esta classe.
 * A subclasse deve ter view_mini_player.xml incluído no seu layout.
 */
abstract class BasePlayerActivity : AppCompatActivity() {

    protected var playerService: AudioPlayerService? = null
    private var serviceBound = false
    private var playerExpanded = false

    // O mini-player exibe o player de áudio OU o indicador de leitura guiada (mutuamente
    // exclusivos). O root fica visível se qualquer um dos dois estiver ativo.
    private var audioVisivel = false
    private var audioVisivelAnterior = false
    private var guidedAtivo = false

    /** Sobrescrito por subclasses pra abrir o mini-player já maximizado quando a reprodução
     * começa (ex.: MainActivity, quando o usuário ouve um audiobook da biblioteca sem ter
     * nada carregado pra conversão — nesse caso não há por que começar minimizado). */
    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
    }

    protected open fun deveExpandirMiniPlayerAoIniciar(): Boolean = false

    private var serviceObserverJob: kotlinx.coroutines.Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            playerService = (service as AudioPlayerService.AudioBinder).getService()
            serviceBound  = true
            serviceObserverJob?.cancel()
            serviceObserverJob = observarEstadoServico()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceObserverJob?.cancel()
            serviceObserverJob = null
            playerService = null
            serviceBound  = false
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioPlayerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        observarSonecaMiniPlayer()
    }

    /** Contagem regressiva da soneca ao lado do 😴 no mini-player — achado real (2026-09-16):
     *  o botão nunca teve essa atualização, só o emoji fixo do XML. A notificação já mostra o
     *  texto certo internamente (`android.text`, confirmado via dumpsys), mas o layout de mídia
     *  redesenhado do Android moderno ignora esse texto e mostra o nome do app no lugar — fora
     *  do controle do app; aqui pelo menos garante que a contagem apareça em algum lugar. */
    private fun observarSonecaMiniPlayer() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SleepTimerManager.remainingMs.collect { restanteMs ->
                    val btn = findViewById<View>(R.id.miniPlayerRoot)
                        ?.findViewById<android.widget.Button?>(R.id.miniPlayerSleepTimer)
                        ?: return@collect
                    btn.text = if (restanteMs != null) {
                        "😴 ${SleepTimerManager.formatRemaining(restanteMs)}"
                    } else {
                        "😴"
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            playerService = null
        }
    }

    private fun observarEstadoServico(): kotlinx.coroutines.Job {
        return lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerService?.state?.collect { ps ->
                    atualizarMiniPlayer(ps)
                }
            }
        }
    }

    private fun atualizarRootVisivel(root: View) {
        root.visibility = if (audioVisivel || guidedAtivo) View.VISIBLE else View.GONE
    }

    private fun atualizarMiniPlayer(state: PlayerState) {
        val root = findViewById<View>(R.id.miniPlayerRoot) ?: return

        audioVisivel = state.isVisible
        val acabouDeIniciar = audioVisivel && !audioVisivelAnterior
        audioVisivelAnterior = audioVisivel
        root.findViewById<View>(R.id.miniPlayerBar)?.visibility =
            if (audioVisivel) View.VISIBLE else View.GONE
        if (!audioVisivel) {
            root.findViewById<View>(R.id.miniPlayerExpanded)?.visibility = View.GONE
            playerExpanded = false
        } else if (acabouDeIniciar && deveExpandirMiniPlayerAoIniciar()) {
            toggleExpandido(root, forcarExpandido = true)
        }
        atualizarRootVisivel(root)
        if (!audioVisivel) return

        val nomeView        = root.findViewById<TextView>(R.id.miniPlayerNome)
        val playPauseMini   = root.findViewById<ImageButton>(R.id.miniPlayerPlayPause)
        val playPauseGrande = root.findViewById<ImageButton>(R.id.miniPlayerPlayPauseGrande)
        val tempoView       = root.findViewById<TextView>(R.id.miniPlayerTempo)
        val seekBar         = root.findViewById<SeekBar>(R.id.miniPlayerSeek)
        val btnLerLivro      = root.findViewById<Button>(R.id.miniPlayerLerLivro)

        val playIcon  = R.drawable.ic_player_play
        val pauseIcon = R.drawable.ic_player_pause

        nomeView.text = state.trackName
        playPauseMini.setImageResource(if (state.isPlaying) pauseIcon else playIcon)
        playPauseGrande.setImageResource(if (state.isPlaying) pauseIcon else playIcon)

        seekBar.max      = state.duration
        seekBar.progress = state.currentPosition
        // Erro de playback (P1): o mini-player mostra a falha no lugar do tempo, em vez de
        // parecer só "pausado" — o botão de play vira "tentar de novo".
        tempoView.text   = state.erroMensagem
            ?: "${formatarTempo(state.currentPosition)} / ${formatarTempo(state.duration)}"

        val livroVinculado =
            com.jonjonesbr.audiobookgen.data.LinkedBookStore.textoVinculado(this, state.caminho)
        btnLerLivro?.visibility = if (livroVinculado != null) View.VISIBLE else View.GONE
        root.findViewById<Button>(R.id.miniPlayerCapitulos)?.visibility =
            if (state.temCapitulos) View.VISIBLE else View.GONE
    }

    protected fun configurarMiniPlayerBotoes() {
        try {
            val root = findViewById<View>(R.id.miniPlayerRoot) ?: return
            // T6.1 (edge-to-edge): mini-player é ancorado na base da tela em várias telas
            // (via <include>) — sem isso, o conteúdo expandido fica atrás da barra de gestos.
            root.aplicarPaddingInferiorComNavigationBar()

            root.findViewById<ImageButton?>(R.id.miniPlayerPlayPause)?.setOnClickListener {
                playerService?.alternarPlayPause()
            }
            root.findViewById<ImageButton?>(R.id.miniPlayerPlayPauseGrande)?.setOnClickListener {
                playerService?.alternarPlayPause()
            }
            root.findViewById<ImageButton?>(R.id.miniPlayerExpandir)?.setOnClickListener {
                toggleExpandido(root)
            }
            root.findViewById<View?>(R.id.miniPlayerBar)?.setOnClickListener {
                toggleExpandido(root)
            }
            root.findViewById<Button?>(R.id.miniPlayerRecuar)?.apply {
                text = "-15s"
                setTextColor(ContextCompat.getColor(this@BasePlayerActivity, R.color.color_on_surface))
                setOnClickListener { playerService?.seekRelativo(-REWIND_MS) }
            }
            root.findViewById<Button?>(R.id.miniPlayerAvancar)?.apply {
                text = "+15s"
                setTextColor(ContextCompat.getColor(this@BasePlayerActivity, R.color.color_on_surface))
                setOnClickListener { playerService?.seekRelativo(REWIND_MS) }
            }
            root.findViewById<ImageButton?>(R.id.miniPlayerFechar)?.setOnClickListener {
                playerService?.fecharPlayer()
            }

            root.findViewById<android.widget.Button?>(R.id.miniPlayerSleepTimer)?.setOnClickListener {
                mostrarDialogoSleepTimerMiniPlayer()
            }
            root.findViewById<Button?>(R.id.miniPlayerLerLivro)?.setOnClickListener {
                abrirLivroVinculado()
            }
            root.findViewById<Button?>(R.id.miniPlayerCapitulos)?.setOnClickListener {
                mostrarTocCapitulos()
            }

            configurarIndicadorGuiado(root)

            val seekBar = root.findViewById<SeekBar?>(R.id.miniPlayerSeek)
            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) playerService?.seek(p, true)
                }
                override fun onStartTrackingTouch(sb: SeekBar)  { playerService?.seek(sb.progress, true)  }
                override fun onStopTrackingTouch(sb: SeekBar)   { playerService?.seek(sb.progress, false) }
            })

            listOf(
                Triple(R.id.miniPlayerVel075, VELOCIDADE_075, "0.75x"),
                Triple(R.id.miniPlayerVel100, 1.0f, "1x"),
                Triple(R.id.miniPlayerVel125, VELOCIDADE_125, "1.25x"),
                Triple(R.id.miniPlayerVel150, VELOCIDADE_150, "1.5x"),
                Triple(R.id.miniPlayerVel200, 2.0f, "2x")
            ).forEach { (id, vel, label) ->
                root.findViewById<Button>(id)?.apply {
                    text = label
                    setTextColor(ContextCompat.getColor(this@BasePlayerActivity, R.color.color_on_surface))
                    setOnClickListener { playerService?.aplicarVelocidade(vel) }
                }
            }
        } catch (e: Exception) {
            Log.w("BasePlayerActivity", "Mini-player não disponível neste layout", e)
        }
    }

    /** Indicador de leitura guiada (some na ReaderActivity, que tem sua própria barra). */
    private fun configurarIndicadorGuiado(root: View) {
        root.findViewById<View?>(R.id.guidedIndicatorBar)?.setOnClickListener {
            abrirLeituraGuiada()
        }
        root.findViewById<ImageButton?>(R.id.guidedIndicatorPlayPause)?.setOnClickListener {
            val m = GuidedPlaybackBridge.manager
            if (m?.state?.value?.isPlaying == true) m.pause() else m?.resume()
        }
        observarIndicadorGuided()
    }

    /** Telas com sua própria UI de leitura guiada (ReaderActivity) sobrescrevem para false. */
    protected open fun mostrarIndicadorGuided(): Boolean = true

    private fun observarIndicadorGuided() {
        if (!mostrarIndicadorGuided()) return
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GuidedPlaybackBridge.activeState.collect { st -> atualizarIndicadorGuided(st) }
            }
        }
    }

    private fun atualizarIndicadorGuided(st: GuidedState?) {
        val root = findViewById<View>(R.id.miniPlayerRoot) ?: return
        val bar = root.findViewById<View>(R.id.guidedIndicatorBar) ?: return
        guidedAtivo = st != null && mostrarIndicadorGuided()
        bar.visibility = if (guidedAtivo) View.VISIBLE else View.GONE
        if (guidedAtivo && st != null) {
            root.findViewById<TextView>(R.id.guidedIndicatorText)?.text =
                getString(
                    R.string.guided_reading_indicator_paragrafo,
                    GuidedPlaybackBridge.trackName,
                    st.index + 1
                )
            root.findViewById<ImageButton>(R.id.guidedIndicatorPlayPause)?.setImageResource(
                if (st.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
            )
        }
        atualizarRootVisivel(root)
    }

    private fun abrirLeituraGuiada() {
        val caminho = GuidedPlaybackBridge.caminhoArquivo
        if (caminho.isEmpty()) return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, caminho)
        )
    }

    /** Abre o livro-texto vinculado ao audiobook atual — ponte de UI player→leitor (Fase 5, item 2). */
    private fun abrirLivroVinculado() {
        val caminhoMp3 = playerService?.state?.value?.caminho ?: return
        val caminhoTexto =
            com.jonjonesbr.audiobookgen.data.LinkedBookStore.textoVinculado(this, caminhoMp3) ?: return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, caminhoTexto)
        )
    }

    /** Sumário tocável de capítulos do audiobook atual (timestamps do MP3). Abre o capítulo
     *  tocado via seek — mesmo mecanismo dos botões recuar/avançar por capítulo. */
    private fun mostrarTocCapitulos() {
        val service = playerService ?: return
        val state = service.state.value
        if (state.capitulos.isEmpty()) return

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_capitulos, null)
        dialog.setContentView(view)

        val posicao = state.currentPosition.toLong()
        val ativo = state.capitulos.indexOfLast { posicao >= it.inicioMs }.coerceAtLeast(0)
        val adapter = CapituloAudioTocAdapter(state.capitulos, ativo) { indice ->
            dialog.dismiss()
            service.seek(state.capitulos[indice].inicioMs.toInt(), false)
        }
        view.findViewById<RecyclerView>(R.id.rvCapitulosToc).apply {
            layoutManager = LinearLayoutManager(this@BasePlayerActivity)
            this.adapter = adapter
        }
        dialog.show()
    }

    private fun mostrarDialogoSleepTimerMiniPlayer() {
        SleepTimerDialog.show(this)
    }

    private fun toggleExpandido(root: View, forcarExpandido: Boolean? = null) {
        val expanded = root.findViewById<View>(R.id.miniPlayerExpanded)
        val btnExpandir = root.findViewById<ImageButton>(R.id.miniPlayerExpandir)
        playerExpanded = forcarExpandido ?: !playerExpanded
        expanded.visibility = if (playerExpanded) View.VISIBLE else View.GONE
        btnExpandir.setImageResource(
            if (playerExpanded) android.R.drawable.arrow_down_float
            else android.R.drawable.arrow_up_float
        )
    }

    private fun formatarTempo(ms: Int): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val sec = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % SEGUNDOS_POR_MINUTO
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    companion object {
        private const val VELOCIDADE_075 = 0.75f
        private const val VELOCIDADE_125 = 1.25f
        private const val VELOCIDADE_150 = 1.5f
        private const val REWIND_MS = 15_000
        private const val SEGUNDOS_POR_MINUTO = 60L
    }
}
