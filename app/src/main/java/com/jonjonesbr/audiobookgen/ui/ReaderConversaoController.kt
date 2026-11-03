package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.ChapterExportPolicy
import com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.dpToPx
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DIALOG_PROGRESSO_PADDING_H = 24
private const val DIALOG_PROGRESSO_PADDING_TOPO = 20
private const val DIALOG_PROGRESSO_TEXT_SIZE_SP = 15f
private const val DIALOG_PROGRESSO_TEXTO_BAIXO_DP = 16
private const val PROGRESSO_MAX = 100

/**
 * Conversão em áudio a partir do leitor: acompanha a geração (diálogo de progresso, aviso de conclusão e
 * "Tentar de novo"), baixa capítulo ou livro, exporta texto e capítulos e abre o audiobook já convertido
 * ("Ouvir") — extraído de [ReaderActivity] pra reduzir o tamanho do arquivo (rb-4). Padrão do projeto:
 * activity + viewModel + callbacks no construtor, sem acessar campos da Activity.
 */
@Suppress("TooManyFunctions") // exportação, conversão e audiobook do leitor: um assunto só
class ReaderConversaoController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val audiobookViewModel: AudiobookViewModel,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterGuidedPlayerManager: () -> GuidedPlayerManager?,
        val obterPlayerService: () -> AudioPlayerService?,
        val obterParagrafoAtual: () -> Int?,
        val caminhoMapaDePersonagens: (List<String>) -> String?,
        val aguardarAnaliseDeFalantes: suspend () -> Unit,
    )

    private val caminhoArquivo: String get() = callbacks.obterCaminhoArquivo()
    private val guidedPlayerManager: GuidedPlayerManager? get() = callbacks.obterGuidedPlayerManager()
    // Lido só dentro dos métodos (não no construtor): ver armadilha de recursos no construtor.
    private val appPrefs by lazy { AppPrefs(activity) }

    private val dialogos = DialogosAbertos(activity)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)
    private val dialogoProgresso = DialogoProgressoConversao(activity, dialogos) {
        audiobookViewModel.cancelarProcessamento()
    }

    /** Última geração de audiobook pedida aqui (capítulo ou livro), para o "Tentar de novo" do aviso de erro. */
    private var refazerConversao: (() -> Unit)? = null

    /** Exporta o texto limpo do leitor para o arquivo escolhido. */
    @Suppress("TooGenericExceptionCaught") // escrita no arquivo do usuário (SD, nuvem) falha de muitos jeitos
    fun exportarTextoLimpo(uri: Uri) {
        val texto = viewModel.textoEfetivo()?.paragrafos
            ?.joinToString("\n\n") { it.texto } ?: return
        // A escrita vai para IO: em nuvem ou cartão SD ela pode travar a tela.
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = activity.contentResolver.openOutputStream(uri)
                        ?: error("Output stream unavailable")
                    output.bufferedWriter().use { it.write(texto) }
                }
                Toast.makeText(activity, R.string.toast_clean_text_exported, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ReaderActivity", "Erro ao exportar texto limpo", e)
                activity.mostrarErro(R.string.toast_clean_text_export_error)
            }
        }
    }

    /**
     * Acompanha a geração do audiobook (progresso, conclusão, erro, pausa). Chamado uma vez, no onCreate da
     * Activity, também no "Ler sem áudio".
     */
    fun observar() {
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                audiobookViewModel.uiState.collect { state ->
                    if (state.processando) {
                        dialogoProgresso.mostrarOuAtualizar(state.progressoPct, state.statusMessage)
                    } else {
                        dialogoProgresso.esconder()
                    }

                    state.conversionDone?.let { evento ->
                        audiobookViewModel.consumirEventoConversao()
                        when (evento) {
                            is ConversionDoneEvent.Sucesso -> {
                                // Aviso com ação "Abrir": toca o audiobook pronto, como o "Ouvir" das conversões.
                                com.google.android.material.snackbar.Snackbar.make(
                                    activity.findViewById(android.R.id.content),
                                    R.string.toast_download_completed,
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                                ).setAction(R.string.dl_abrir_livro) {
                                    abrirAudiobookGerado(evento.caminho)
                                }.show()
                            }
                            is ConversionDoneEvent.Cancelada -> {
                                Toast.makeText(
                                    activity,
                                    R.string.status_conversion_canceled,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is ConversionDoneEvent.Erro -> {
                                val msg = activity.getString(R.string.toast_download_failed, evento.mensagem)
                                // "Tentar de novo" refaz a mesma geração (capítulo ou livro) a partir do texto
                                // atual.
                                activity.mostrarErro(
                                    msg,
                                    refazerConversao?.let { activity.getString(R.string.acao_tentar_de_novo) to it }
                                )
                            }
                            is ConversionDoneEvent.Pausada -> {
                                Toast.makeText(
                                    activity,
                                    R.string.status_conversion_paused,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // escrita do arquivo temporário falha de muitos jeitos e vira aviso
    fun baixarCapitulo() {
        val texto = viewModel.textoEfetivo() ?: return
        refazerConversao = ::baixarCapitulo
        val capAtivo = viewModel.uiState.value.capituloAtivo
        val cap = texto.capitulos.getOrNull(capAtivo)
        val trechoTexto = cap?.let { textoDoCapitulo(texto, capAtivo) }
        if (cap == null || trechoTexto == null) return

        val nomeSemExt = File(caminhoArquivo).nameWithoutExtension
        val nomeCapitulo = "${nomeSemExt} - ${cap.titulo}"

        // Vozes dos personagens também no capítulo avulso (o mapa serve a qualquer trecho contínuo do livro).
        val textosDoLivro = texto.paragrafos.map { it.texto }
        val speakerMapPath = callbacks.caminhoMapaDePersonagens(textosDoLivro)
        val arquivoTemp = File(activity.cacheDir, "download_chapter_${System.currentTimeMillis()}.txt")
        try {
            arquivoTemp.writeText(trechoTexto, Charsets.UTF_8)
            iniciarDownloadBackground(
                arquivoTemp.absolutePath,
                nomeCapitulo,
                speakerMapPath = speakerMapPath,
                caminhoDoLivro = caminhoArquivo
            )
        } catch (e: Exception) {
            Log.w("ReaderActivity", "Erro ao preparar download de capítulo", e)
            activity.mostrarErro(
                activity.getString(R.string.toast_erro_preparar_download, activity.getString(mensagemDeErro(e)))
            )
        }
    }

    /**
     * "Gerar audiobook › Livro inteiro": abre o painel da tela do livro (voz, ritmo, tempo estimado, pasta, fila).
     * Com o texto editado ou limpo aqui no leitor, ou um arquivo fora da biblioteca, gera direto deste texto.
     */
    fun gerarLivroInteiro() {
        val state = viewModel.uiState.value
        activity.lifecycleScope.launch {
            val livro = if (state.temEdicoes || state.textoLimpo != null) {
                null
            } else {
                com.jonjonesbr.audiobookgen.data.BibliotecaStore.porCaminho(
                    activity.applicationContext,
                    caminhoArquivo
                )
            }
            if (livro == null) {
                baixarLivroCompleto()
            } else {
                activity.startActivity(
                    Intent(activity, LivroActivity::class.java)
                        .putExtra(LivroActivity.EXTRA_ID, livro.id)
                        .putExtra(LivroActivity.EXTRA_ABRIR_PAINEL_GERAR, true)
                        .putExtra(LivroActivity.EXTRA_SO_PAINEL, true)
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // escrita do arquivo temporário falha de muitos jeitos e vira aviso
    private fun baixarLivroCompleto() {
        val texto = viewModel.textoEfetivo() ?: return
        refazerConversao = ::baixarLivroCompleto
        val nomeSemExt = File(caminhoArquivo).nameWithoutExtension
        activity.lifecycleScope.launch {
            callbacks.aguardarAnaliseDeFalantes()
            val trechoTexto = texto.paragrafos.joinToString("\n\n") { it.texto }
            val textosDoLivro = texto.paragrafos.map { it.texto }
            val speakerMapPath = callbacks.caminhoMapaDePersonagens(textosDoLivro)
            val arquivoTemp = File(activity.cacheDir, "download_book_${System.currentTimeMillis()}.txt")
            try {
                arquivoTemp.writeText(trechoTexto, Charsets.UTF_8)
                iniciarDownloadBackground(
                    arquivoTemp.absolutePath,
                    nomeSemExt,
                    livroOrigemCaminho = caminhoArquivo,
                    speakerMapPath = speakerMapPath
                )
            } catch (e: Exception) {
                Log.w("ReaderActivity", "Erro ao preparar download de livro completo", e)
                activity.mostrarErro(
                    activity.getString(R.string.toast_erro_preparar_download, activity.getString(mensagemDeErro(e)))
                )
            }
        }
    }

    /** Exporta cada capítulo do livro como um arquivo de texto na pasta escolhida. */
    fun exportarCapitulosPara(pastaUri: Uri) {
        val texto = viewModel.textoEfetivo()
        if (texto == null || texto.capitulos.isEmpty()) {
            Toast.makeText(activity, R.string.toast_sem_capitulos_para_exportar, Toast.LENGTH_SHORT).show()
            return
        }
        val nomeLivro = File(caminhoArquivo).nameWithoutExtension
        val arquivos = ChapterExportPolicy.montarArquivosCapitulos(texto.capitulos, texto.paragrafos)
        activity.lifecycleScope.launch {
            val resultado = DocumentExportUseCase(activity)
                .exportarTextos("${nomeLivro}_capitulos", arquivos, pastaUri)
            val mensagem = if (resultado.isSuccess) {
                val total = resultado.getOrDefault(0)
                activity.resources.getQuantityString(R.plurals.toast_capitulos_exportados_qtd, total, total)
            } else {
                activity.getString(
                    R.string.toast_erro_exportar_capitulos,
                    resultado.exceptionOrNull()?.let { activity.getString(mensagemDeErro(it)) }
                        ?: activity.getString(R.string.erro_amigavel_generico)
                )
            }
            if (resultado.isSuccess) {
                Toast.makeText(activity, mensagem, Toast.LENGTH_LONG).show()
            } else {
                activity.mostrarErro(mensagem)
            }
        }
    }

    private fun iniciarDownloadBackground(
        caminhoTexto: String,
        nomeAudiobook: String,
        livroOrigemCaminho: String? = null,
        speakerMapPath: String? = null,
        caminhoDoLivro: String? = livroOrigemCaminho
    ) {
        val iniciou = audiobookViewModel.iniciarProcessamentoDeTexto(
            caminhoTexto,
            nomeAudiobook,
            livroOrigemCaminho,
            speakerMapPath,
            caminhoDoLivro
        )
        if (!iniciou) {
            activity.mostrarErro(R.string.conv_pausada_bloqueia)
            return
        }
        // Avisa que começou e leva ao andamento (antes a conversão iniciada aqui não tinha onde ser acompanhada).
        com.google.android.material.snackbar.Snackbar.make(
            activity.findViewById(android.R.id.content),
            activity.getString(R.string.conv_iniciada_do_leitor),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction(R.string.conv_ver) {
            activity.startActivity(Intent(activity, ConversoesActivity::class.java))
        }.show()
    }

    /** Toca o audiobook recém-gerado e leva à biblioteca (mesmo fluxo do "Ouvir" em [ConversoesActivity]). */
    private fun abrirAudiobookGerado(caminhoMp3: String) {
        // Ouvir o audiobook encerra a leitura guiada, como em [ouvirAudiobookDesteLivro].
        guidedPlayerManager?.stop()
        audiobookViewModel.iniciarPlayer(caminhoMp3, File(caminhoMp3).nameWithoutExtension)
        activity.startActivity(Intent(activity, LibraryActivity::class.java))
    }

    /**
     * Abre o audiobook já convertido deste livro (vínculo criado em [baixarLivroCompleto],
     * via [com.jonjonesbr.audiobookgen.data.LinkedBookStore]) — ponte de UI entre leitura e
     * escuta (Fase 5, item 2). Começa do parágrafo da guiada (se ela tocava este livro) ou do
     * visível, pelas âncoras de sincronia; sem âncoras, retoma da posição salva.
     */
    fun ouvirAudiobookDesteLivro() {
        val mp3 = com.jonjonesbr.audiobookgen.data.LinkedBookStore.mp3Vinculado(activity, caminhoArquivo)
            ?: return
        if (!File(mp3).exists()) return
        // Parágrafo de partida: o da leitura guiada, se ela estava tocando este livro; senão o da tela.
        val guiada = guidedPlayerManager
        val paragrafoPartida = paragrafoDePartida(
            indiceGuiada = guiada?.state?.value?.index,
            caminhoGuiada = guiada?.caminhoArquivo,
            caminhoTela = caminhoArquivo
        ) { callbacks.obterParagrafoAtual() }
        val nome = File(mp3).nameWithoutExtension
        // O cálculo (âncoras do MP3, lidas do disco) sai da main. A guiada só para depois dele: se a Activity
        // for destruída no meio, a corrotina é cancelada e nenhuma das duas coisas acontece.
        activity.lifecycleScope.launch {
            val posicao = paragrafoPartida?.let { viewModel.posicaoDoParagrafoNoAudiobook(mp3, it) }
            // Se a pessoa saiu do app durante o cálculo, não se inicia o serviço em segundo plano (Android 12+
            // lança exceção); a guiada segue como estava.
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            // Ouvir o audiobook encerra a leitura guiada: a barra dela sai e o mini player do audiobook aparece.
            guiada?.stop()
            val service = callbacks.obterPlayerService()
            if (service != null) {
                service.iniciar(mp3, nome, posicao)
            } else {
                val intent = Intent(activity, AudioPlayerService::class.java).apply {
                    action = AudioPlayerService.ACTION_INICIAR
                    putExtra(AudioPlayerService.EXTRA_CAMINHO, mp3)
                    putExtra(AudioPlayerService.EXTRA_NOME, nome)
                    posicao?.let { putExtra(AudioPlayerService.EXTRA_POSICAO_MS, it) }
                }
                activity.startForegroundService(intent)
            }
        }
    }

    /** Segue direto se o motor da voz é offline e está pronto; senão exige internet. */
    fun verificarConectividadeAntesDeConverter(prosseguir: () -> Unit) {
        // O motor salvo pode divergir do que vai sintetizar de fato (voz Supertonic com
        // motor "edge" salvo, por ex.) — decide pela voz selecionada.
        val motorEfetivo = VoiceCatalog.effectiveEngine(
            appPrefs.vozSelecionada.orEmpty(), appPrefs.motorTts
        )
        // "Motor Android" só é realmente offline se já existe uma voz do dispositivo
        // configurada (ID composto "android::pkg::nome") — sem isso, motorTts="android" é um
        // estado inválido (não dá pra sintetizar nada) e não deve ser tratado como pronto.
        val temVozAndroidValida = com.jonjonesbr.audiobookgen.tts.AndroidVoiceId.isAndroidVoice(
            appPrefs.vozSelecionada.orEmpty()
        )
        val prontoOffline = motorProntoOffline(motorEfetivo, temVozAndroidValida) {
            VoiceCatalog.modeloProntoParaVoz(appPrefs.vozSelecionada.orEmpty(), activity)
        }
        if (prontoOffline) {
            prosseguir()
            return
        }

        val online = com.jonjonesbr.audiobookgen.util.NetworkStatus.isOnline(activity)

        if (!online) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.offline_titulo))
                .setMessage(activity.getString(R.string.offline_mensagem))
                .setNegativeButton(activity.getString(R.string.offline_btn_ok), null)
                .setPositiveButton(activity.getString(R.string.offline_btn_alternar)) { _, _ ->
                    if (temVozAndroidValida) {
                        appPrefs.motorTts = "android"
                        prosseguir()
                    } else {
                        Toast.makeText(
                            activity,
                            R.string.erro_precisa_configurar_voz_dispositivo,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .exibir()
        } else {
            prosseguir()
        }
    }
}

/**
 * Diálogo de progresso da conversão: barra e texto de status, com "Cancelar" (que chama [aoCancelar]).
 * Não fecha com toque fora e some quando a geração termina ([esconder]).
 */
private class DialogoProgressoConversao(
    private val activity: AppCompatActivity,
    private val dialogos: DialogosAbertos,
    private val aoCancelar: () -> Unit,
) {
    private var dialogProgressoConversao: AlertDialog? = null
    private var tvDialogProgressoStatus: TextView? = null
    private var barDialogProgresso: ProgressBar? = null

    fun mostrarOuAtualizar(progresso: Int, mensagem: String) {
        if (activity.isFinishing) return
        if (dialogProgressoConversao == null) {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    activity.dpToPx(DIALOG_PROGRESSO_PADDING_H),
                    activity.dpToPx(DIALOG_PROGRESSO_PADDING_TOPO),
                    activity.dpToPx(DIALOG_PROGRESSO_PADDING_H),
                    activity.dpToPx(DIALOG_PROGRESSO_PADDING_H)
                )
            }
            tvDialogProgressoStatus = TextView(activity).apply {
                text = activity.getString(R.string.reader_progresso_iniciando)
                textSize = DIALOG_PROGRESSO_TEXT_SIZE_SP
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurface
                    )
                )
                setPadding(0, 0, 0, activity.dpToPx(DIALOG_PROGRESSO_TEXTO_BAIXO_DP))
            }
            barDialogProgresso = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = PROGRESSO_MAX
                progress = 0
            }
            container.addView(tvDialogProgressoStatus)
            container.addView(barDialogProgresso)

            dialogProgressoConversao = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.toast_download_started))
                .setView(container)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ -> aoCancelar() }
                .create()
            dialogProgressoConversao?.let { dialogos.mostrar(it) }
        }

        tvDialogProgressoStatus?.text = mensagem
        barDialogProgresso?.progress = progresso
    }

    fun esconder() {
        dialogProgressoConversao?.dismiss()
        dialogProgressoConversao = null
        tvDialogProgressoStatus = null
        barDialogProgresso = null
    }
}

/**
 * Motor pronto para sintetizar sem internet: Android só com voz do dispositivo válida, Supertonic sempre,
 * Kokoro se o modelo estiver baixado (lido só nesse caso, por [kokoroPronto]).
 */
internal fun motorProntoOffline(
    motorEfetivo: String,
    temVozAndroidValida: Boolean,
    kokoroPronto: () -> Boolean,
): Boolean = when (motorEfetivo) {
    "android" -> temVozAndroidValida
    "onnx" -> true // Supertonic sintetiza offline
    "kokoro" -> kokoroPronto()
    else -> false
}

/**
 * Texto do capítulo [indice], parágrafo a parágrafo, com os limites ajustados ao texto.
 * Nulo se o capítulo não existe.
 */
internal fun textoDoCapitulo(texto: TextoExtraido, indice: Int): String? {
    val cap = texto.capitulos.getOrNull(indice) ?: return null
    return texto.paragrafos.subList(
        cap.indiceParagrafoInicio.coerceAtLeast(0),
        (cap.indiceParagrafoFim + 1).coerceAtMost(texto.paragrafos.size)
    ).joinToString("\n\n") { it.texto }
}

/**
 * Parágrafo em que a escuta do audiobook começa: o da leitura guiada, se ela estava tocando este livro
 * (índice não negativo e mesmo caminho); senão o parágrafo visível na tela ([naTela]).
 */
internal fun paragrafoDePartida(
    indiceGuiada: Int?,
    caminhoGuiada: String?,
    caminhoTela: String,
    naTela: () -> Int?,
): Int? =
    if (indiceGuiada != null && indiceGuiada >= 0 && caminhoGuiada == caminhoTela) indiceGuiada else naTela()
