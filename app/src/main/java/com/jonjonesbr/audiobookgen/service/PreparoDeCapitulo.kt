package com.jonjonesbr.audiobookgen.service

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.TtsErrorMapper
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Preparo do capítulo (pré-renderização): sintetiza com antecedência as unidades que a leitura guiada vai pedir
 * (as MESMAS de play(): segmentos por personagem, orações ou parágrafo inteiro) e as deixa no cache de áudio em
 * disco. Depois disso a leitura só encontra arquivos prontos, sem esperar o motor. O preparo corre à frente da
 * leitura (rb-25): pula o que a leitura ao vivo já cobre, cede a vez quando ela sintetiza e, no Kokoro (motor
 * lento), só anda com a leitura parada, para não atrasar a fala.
 *
 * Saiu do [GuidedPlayerManager]; ele continua dono do estado da leitura e entrega aqui só lambdas ([Callbacks]).
 */
internal class PreparoDeCapitulo(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val callbacks: Callbacks
) {
    /** Tudo que o preparo precisa ler da leitura ao vivo. Só lambdas, avaliadas a cada uso. */
    @Suppress("LongParameterList")
    class Callbacks(
        val ritmoAtual: () -> Int,
        val vozAtual: () -> String,
        val estadoDaLeitura: () -> GuidedState,
        val leituraPausada: () -> Boolean,
        val leituraSintetizando: () -> Boolean,
        val criarSintetizador: (String) -> TtsSynthesizer,
        val liberarGerenciador: () -> Unit
    )

    companion object {
        private const val TAG = "PreparoDeCapitulo"
        private const val ESPERA_PRERENDER_MS = 1_000L
        private const val MAX_FALHAS_PRERENDER = 5

        /** Motor mais lento que a fala: o preparo só anda com a leitura parada (rb-25). */
        internal const val MOTOR_LENTO_PREPARO = "kokoro"
    }

    private var preRenderJob: Job? = null

    /** A tela que iniciou o preparo já foi fechada: libera o gerenciador quando o preparo terminar. */
    @Volatile var liberarAoFim = false

    /**
     * Cancelamento que a notificação do preparo pede ([PreRenderBridge.cancelar]). Fica numa propriedade para
     * limpar só a própria referência: senão o bridge segurava o gerenciador depois do release().
     */
    private val cancelarPeloBridge: () -> Unit = { cancelar() }

    /** Tira o cancelamento do bridge só se ainda for o deste preparo (outro pode tê-lo assumido depois). */
    fun soltarCancelamentoDoBridge() {
        if (PreRenderBridge.cancelar === cancelarPeloBridge) PreRenderBridge.cancelar = null
    }

    fun ativo(): Boolean = preRenderJob?.isActive == true

    fun cancelar() {
        val job = preRenderJob ?: return
        if (job.isActive) {
            job.cancel()
            PreRenderBridge.state.update { it?.copy(cancelado = true) }
        }
        preRenderJob = null
    }

    /**
     * Espera a vez antes de cada unidade do preparo: a conversão de audiobook ativa primeiro, depois a leitura ao vivo
     * (ver [PreparoAFrente.esperaALeitura]). Só a Main lê o estado da leitura, então não roda dentro de IO.
     */
    private suspend fun esperarVez(lento: Boolean) {
        while (PythonEngineUseCase.conversaoAtiva()) {
            PreRenderBridge.state.update { it?.copy(esperandoConversao = true) }
            delay(ESPERA_PRERENDER_MS)
        }
        PreRenderBridge.state.update { it?.copy(esperandoConversao = false) }
        while (PreparoAFrente.esperaALeitura(
                lento, callbacks.estadoDaLeitura().isPlaying, callbacks.leituraSintetizando(),
                callbacks.leituraPausada()
            )
        ) {
            // Só o motor lento mostra "esperando a leitura parar": nos outros, a espera é curta e a nota piscaria.
            if (lento) PreRenderBridge.state.update { it?.copy(esperandoLeitura = true) }
            delay(ESPERA_PRERENDER_MS)
        }
        PreRenderBridge.state.update { it?.copy(esperandoLeitura = false) }
    }

    /**
     * Começa a preparar as [unidades] (parágrafo, (texto, voz)). [titulo] aparece na notificação. Não faz nada
     * se já houver preparo em curso ou se não houver unidades.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun iniciar(
        unidades: List<Pair<Int, Pair<String, String>>>,
        voz: String,
        ritmo: Int,
        motorEfetivo: String,
        titulo: String
    ) {
        if (ativo() || unidades.isEmpty()) return
        val lento = motorEfetivo == MOTOR_LENTO_PREPARO
        val paragrafosDasUnidades = unidades.map { it.first }

        PreRenderBridge.state.value = PreRenderState(0, unidades.size, titulo)
        PreRenderBridge.cancelar = cancelarPeloBridge
        PreRenderService.start(context)

        preRenderJob = coroutineScope.launch {
            // Identidade deste preparo: o finally só solta o cancelamento se nenhum preparo novo o tiver assumido.
            val esteJob = currentCoroutineContext()[Job]
            var feitos = 0
            var falhasSeguidas = 0
            val sintetizadores = mutableMapOf<String, TtsSynthesizer>()
            try {
                var cursor = 0
                while (cursor < unidades.size) {
                    // Conversão ativa, leitura ao vivo sintetizando (ou tocando, no motor lento): espera a vez.
                    esperarVez(lento)
                    // Voz ou ritmo mudaram no meio: o que já foi feito ficou com a chave antiga; para aqui.
                    // Depois das esperas: uma troca durante a espera não pode sintetizar um trecho com a chave velha.
                    if (callbacks.vozAtual() != voz || callbacks.ritmoAtual() != ritmo) {
                        PreRenderBridge.state.update { it?.copy(cancelado = true) }
                        return@launch
                    }
                    // A leitura já cobre (ou passou de) trechos da frente do cursor: pula, a leitura cuida deles.
                    // Pausada, parada ou com erro não há leitura ao vivo: a janela fica só para trás do em leitura.
                    val estado = callbacks.estadoDaLeitura()
                    val aoVivo = !callbacks.leituraPausada() && (estado.isPlaying || estado.isLoading)
                    val alvo = PreparoAFrente.proximaUnidade(
                        paragrafosDasUnidades, cursor, estado.index, PreparoAFrente.janelaDaLeitura(aoVivo)
                    )
                    if (alvo > cursor) {
                        feitos += alvo - cursor
                        cursor = alvo
                        PreRenderBridge.state.update { it?.copy(feitos = feitos) }
                        continue
                    }
                    val (texto, vozUnidade) = unidades[cursor].second
                    val motorDaUnidade = VoiceCatalog.effectiveEngine(vozUnidade, motorEfetivo)
                    val sintetizador = sintetizadores.getOrPut(motorDaUnidade) {
                        callbacks.criarSintetizador(motorDaUnidade)
                    }
                    val caminho = withContext(Dispatchers.IO) { sintetizador.synthesize(texto, vozUnidade, ritmo) }
                    if (caminho == null) {
                        // A leitura passou deste trecho: não é falha. Se o produtor que o preparo esperava
                        // foi cancelado, o TtsChunkCache já deu ao preparo uma tentativa própria (rb-25b).
                        val ficouParaALeitura = PreparoAFrente.ficouParaALeitura(
                            paragrafosDasUnidades[cursor], callbacks.estadoDaLeitura().index
                        )
                        if (!ficouParaALeitura) {
                            falhasSeguidas++
                            val motivo = sintetizador.ultimoErro() ?: "motivo desconhecido"
                            CrashLogWriter.log(
                                null, "prerender", "Falha ao preparar trecho: voz=$vozUnidade, motivo=$motivo"
                            )
                            if (falhasSeguidas >= MAX_FALHAS_PRERENDER) {
                                PreRenderBridge.state.update {
                                    it?.copy(erro = context.getString(TtsErrorMapper.toUserMessageRes(motivo)))
                                }
                                return@launch
                            }
                        } else {
                            // Não conta como falha (a leitura cuida do trecho), mas deixa rastro: sem ele, uma falha
                            // real de P ou P+1 some sem log.
                            Log.w(
                                TAG,
                                "Preparo: trecho do parágrafo ${paragrafosDasUnidades[cursor]} não sintetizado " +
                                    "perto da leitura (motor=$motorDaUnidade, motivo=${sintetizador.ultimoErro()})"
                            )
                        }
                    } else {
                        falhasSeguidas = 0
                    }
                    feitos++
                    cursor++
                    PreRenderBridge.state.update { it?.copy(feitos = feitos) }
                }
                PreRenderBridge.state.update { it?.copy(feitos = unidades.size, concluido = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Preparo do capítulo falhou", e)
                CrashLogWriter.log(e, "prerender", "voz=$voz")
                PreRenderBridge.state.update {
                    it?.copy(erro = context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "")))
                }
            } finally {
                // Com um preparo novo já em curso (preRenderJob outro), o cancelamento é dele.
                if (preRenderJob == null || preRenderJob === esteJob) soltarCancelamentoDoBridge()
                if (liberarAoFim && callbacks.estadoDaLeitura().index < 0) {
                    preRenderJob = null
                    callbacks.liberarGerenciador()
                }
            }
        }
    }
}
