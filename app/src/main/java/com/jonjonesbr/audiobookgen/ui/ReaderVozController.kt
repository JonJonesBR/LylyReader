package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.media.AudioManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PARAGRAFOS_PREVIEW = 3
private const val PREPARO_PARAGRAFOS_SEM_CAPITULO = 120
/** Teto da espera da prévia (rb-24): cobre o caso de a geração nunca baixar (síntese com exceção). */
private const val ESPERA_MAXIMA_DA_PREVIA_MS = 180_000L

/**
 * Faixa de parágrafos do "Preparar capítulo": começa no [inicioPedido] (limitado à lista) e termina no fim do
 * capítulo desse parágrafo; sem capítulo, vai [PREPARO_PARAGRAFOS_SEM_CAPITULO] parágrafos adiante. Só com
 * [paragrafos] não vazio.
 */
internal fun faixaDoPreparo(
    paragrafos: List<Paragrafo>,
    capitulos: List<Capitulo>,
    inicioPedido: Int,
): Pair<Int, Int> {
    val inicio = inicioPedido.coerceIn(0, paragrafos.lastIndex)
    val capitulo = capitulos.getOrNull(paragrafos[inicio].indiceCapitulo)
    val fim = (capitulo?.indiceParagrafoFim ?: (inicio + PREPARO_PARAGRAFOS_SEM_CAPITULO))
        .coerceIn(inicio, paragrafos.lastIndex)
    return inicio to fim
}

/**
 * Amostra da voz: junta [quantos] parágrafos com texto, a partir de [inicio] (rb-31: o trecho atual, não o começo do
 * livro). O início é limitado à lista. Nulo quando não há texto útil (usa-se o texto padrão).
 */
internal fun textoDaAmostra(paragrafos: List<Paragrafo>?, inicio: Int, quantos: Int = PARAGRAFOS_PREVIEW): String? {
    val lista = paragrafos?.takeIf { it.isNotEmpty() } ?: return null
    val partida = inicio.coerceIn(0, lista.lastIndex)
    return lista.asSequence()
        .drop(partida)
        .map { it.texto }
        .filter { it.isNotBlank() }
        .take(quantos)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

/**
 * Voz e pronúncia do leitor: escolha de voz (e a voz recém-clonada), amostra, "Ouvir pronúncia" e dicionário de
 * pronúncias — extraído de [ReaderActivity] pra reduzir o tamanho do arquivo (rb-14). Padrão
 * do projeto: activity + viewModel + callbacks no construtor, sem acessar campos da Activity. Os lançadores de
 * clone e o VoiceSelectionDelegate da Activity só delegam para cá por lambda, porque registram lançadores no
 * construtor (antes de STARTED).
 *
 * Pausa da leitura guiada enquanto a pessoa ouve (rb-24): a pronúncia (uma palavra ou o dicionário) e a amostra de
 * voz pausam a guiada que estiver tocando e retomam depois, só se nada mexeu nela (regra em [PausaParaOuvir]).
 */
class ReaderVozController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val audiobookViewModel: AudiobookViewModel,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterGuidedPlayerManager: () -> GuidedPlayerManager?,
    )

    private val caminhoArquivo: String get() = callbacks.obterCaminhoArquivo()
    /** Nulo no modo "Ler sem áudio". Todo acesso trata o nulo. */
    private val guidedPlayerManager: GuidedPlayerManager? get() = callbacks.obterGuidedPlayerManager()
    // Lido só dentro dos métodos (não no construtor): o construtor pode rodar antes de a Activity estar pronta.
    private val appPrefs by lazy { AppPrefs(activity) }

    /** Segura a leitura guiada enquanto a pessoa ouve. Só guarda lambdas: a regra fica em [PausaParaOuvir]. */
    private val pausaParaOuvir = PausaParaOuvir(
        obterManager = { guidedPlayerManager?.let { GuidedPlaybackBridge.manager } },
        previewEmCurso = audiobookViewModel::previewEmCursoAgora,
        chamadaEmCurso = { emChamada() },
        estadoDaTela = { estadoDaTela(activity) },
    )

    /**
     * Ação "Ouvir" de todo o fluxo do dicionário: toca e segura a leitura em cada diálogo aberto (rb-24). Também
     * vai para o controller de personagens, que abre o mesmo dicionário e assim fica acompanhado do mesmo jeito.
     */
    internal val ouvirNoFluxo: OuvirNoFluxo =
        OuvirNoFluxo(tocar = ::ouvirPronuncia, aoMostrar = pausaParaOuvir::acompanhar)

    /** Chamada telefônica em curso (modo diferente de normal): a guiada não retoma por cima dela. */
    private fun emChamada(): Boolean {
        val modo = activity.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        return modo != AudioManager.MODE_NORMAL
    }

    /** Escolha de voz do leitor (lista de vozes ou voz recém-clonada): grava a voz e refaz o trecho atual. */
    fun selecionarVoz(voice: VoiceOption) {
        appPrefs.vozSelecionada = voice.id
        appPrefs.motorTts = voice.engine
        guidedPlayerManager?.registrarVozEscolhida(voice.id, voice.engine)

        if (voice.engine == "gemini") {
            val chave = SecurePreferences.getGeminiKeys(activity)
            if (chave.isNullOrBlank()) {
                val intent = Intent(activity, SettingsActivity::class.java).apply {
                    putExtra("focar_chave_gemini", true)
                }
                activity.startActivity(intent)
            }
        }
        if (voice.engine == "elevenlabs" &&
            SecurePreferences.getElevenLabsKey(activity).isNullOrBlank()
        ) {
            val intent = Intent(activity, SettingsActivity::class.java).apply {
                putExtra("focar_chave_elevenlabs", true)
            }
            activity.startActivity(intent)
        }

        // Troca de voz refaz o trecho atual sem sair do modo manual: não é um pedido de ponto da narração.
        val guidedIndex = guidedPlayerManager?.state?.value?.index ?: -1
        if (guidedIndex >= 0) {
            // Segura em volta: o pararPreview solta a segurada da amostra, e sem esta a guiada retomaria a voz
            // antiga até a síntese da nova ficar pronta. Solta depois do play, que já mudou o contador de comandos,
            // então a regra não retoma (rb-24b).
            val segura = pausaParaOuvir.segurar()
            // Para a amostra que estiver tocando: a voz nova já vai narrar o trecho (rb-24).
            audiobookViewModel.pararPreview()
            guidedPlayerManager?.play(guidedIndex)
            segura.soltar()
        }
    }

    /**
     * Amostra da voz escolhida na lista de vozes: gera um trecho do livro e avisa quando termina. A guiada pausa
     * enquanto a amostra é gerada e toca, e retoma depois (rb-24). Sem chave do ElevenLabs não toca nada: não segura.
     */
    fun ouvirAmostra(voice: VoiceOption, onFinished: () -> Unit) {
        if (voice.engine == "elevenlabs" &&
            SecurePreferences.getElevenLabsKey(activity).isNullOrBlank()
        ) {
            audiobookViewModel.pararPreview()
            Toast.makeText(
                activity,
                R.string.toast_elevenlabs_configure_key,
                Toast.LENGTH_LONG
            ).show()
            onFinished()
            return
        }
        val segura = pausaParaOuvir.segurar()
        audiobookViewModel.pararPreview()
        val ritmo = appPrefs.ritmo
        val chave = SecurePreferences.getGeminiKeys(activity)
        audiobookViewModel.prepararConfiguracoesPython(voice.engine, chave, appPrefs.pausaMs, caminhoArquivo)
        // Começa no trecho que a pessoa está vendo ou ouvindo, não no começo do livro (rb-31).
        val inicio = indiceDoTrechoAtual(guidedPlayerManager?.state?.value?.index) { callbacks.obterParagrafoAtual() }
        val previewText = textoDaAmostra(viewModel.textoEfetivo()?.paragrafos, inicio.coerceAtLeast(0))
            ?: VoiceCatalog.previewSampleText(voice)
        // UNDISPATCHED: o finally solta a segurada mesmo com o escopo já cancelado (giro de tela).
        activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                audiobookViewModel.gerarAmostra(voice.id, ritmo, voice.engine, chave, previewText)
                audiobookViewModel.uiState.first { !it.amostraGerando }
                onFinished()
                aguardarFimDaPrevia()
            } finally {
                segura.soltar()
            }
        }
    }

    /**
     * Toca [texto] (a pronúncia digitada) com a voz e o motor do livro. Segura a leitura guiada até a prévia acabar
     * (rb-24). A ordem importa: segurar antes do pararPreview, senão a espera da prévia anterior pode soltar a
     * segurada, levar o contador a zero e retomar por um instante.
     */
    fun ouvirPronuncia(texto: String) {
        val manager = guidedPlayerManager ?: return
        val segura = pausaParaOuvir.segurar()
        val (voz, motorSalvo) = manager.vozEMotorDoLivro()
        val motor = VoiceCatalog.effectiveEngine(voz, motorSalvo)
        val chave = SecurePreferences.getGeminiKeys(activity)
        audiobookViewModel.pararPreview()
        audiobookViewModel.prepararConfiguracoesPython(motor, chave, appPrefs.pausaMs, caminhoArquivo)
        audiobookViewModel.ouvirTexto(voz, appPrefs.ritmo, motor, texto, caminhoArquivo)
        soltarAoFimDaPrevia(segura)
    }

    /** Solta [segura] quando a prévia acabar (ou o teto estourar). UNDISPATCHED: roda mesmo com o escopo cancelado. */
    private fun soltarAoFimDaPrevia(segura: PausaParaOuvir.Segura) {
        activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                aguardarFimDaPrevia()
            } finally {
                segura.soltar()
            }
        }
    }

    /** Espera a geração e a reprodução da prévia acabarem, com teto (rb-24). */
    private suspend fun aguardarFimDaPrevia() {
        withTimeoutOrNull(ESPERA_MAXIMA_DA_PREVIA_MS) { audiobookViewModel.previewEmCurso.first { !it } }
    }

    /** Dicionário de pronúncias do livro, com o botão de sugerir pronúncias por IA. */
    fun abrirDicionarioPronuncia() {
        PronunciationDialog.showManager(
            activity, caminhoArquivo, ouvirNoFluxo,
            onSugerirIa = {
                val paragrafos = viewModel.textoEfetivo()?.paragrafos?.map { it.texto }.orEmpty()
                PronunciationAiFlow.start(activity, paragrafos, caminhoArquivo)
            }
        )
    }

    /** Diálogo de uma palavra (toque longo): a guiada pausa enquanto o diálogo e o "Ouvir" estiverem abertos. */
    fun abrirPronunciaDaPalavra(palavra: String) {
        PronunciationDialog.show(activity, palavra, caminhoDoLivro = caminhoArquivo, onOuvir = ouvirNoFluxo)
    }
}
