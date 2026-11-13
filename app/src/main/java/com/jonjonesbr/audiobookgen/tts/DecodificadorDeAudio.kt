package com.jonjonesbr.audiobookgen.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// Decodificação de áudio comprimido para PCM, usada na referência de voz. Fica separada do
// VoiceReferenceAudio para o laço com prazos (e com portas para teste) não crescer aquele objeto.

/** Espera de cada dequeue de buffer, em microssegundos. */
private const val ESPERA_US = 10_000L

// Prazos do laço: sem buffer de saída por 10 s, ou 2 min no total, ele para e devolve o que já
// decodificou. Sem isso, um decoder que só responde TRY_AGAIN_LATER gira para sempre.
private const val SEM_PROGRESSO_MS = 10_000L
private const val TETO_TOTAL_MS = 120_000L

// Lê no máximo ~95 s decodificados: basta para cortar em 30 s depois de reamostrar.
private const val SEGUNDOS_DECODIFICADOS = PocketCustomVoices.MAX_IMPORT_SECONDS + 5L

/** O que o laço precisa do decodificador — em produção, um [MediaCodec] embrulhado. */
internal interface Decodificador {
    /** configure + start do codec; uma falha aqui ainda passa pelo stop/release do fim. */
    fun iniciar()

    fun desenfileirarEntrada(esperaUs: Long): Int

    fun desenfileirarSaida(info: InfoDoBuffer, esperaUs: Long): Int

    fun bufferDeEntrada(indice: Int): ByteBuffer?

    fun bufferDeSaida(indice: Int): ByteBuffer?

    fun enfileirarEntrada(indice: Int, tamanho: Int, instanteUs: Long, flags: Int)

    fun liberarSaida(indice: Int)

    val formatoDeSaida: FormatoDeSaida

    fun parar()

    fun liberar()
}

/** O que o laço precisa da fonte de amostras comprimidas — em produção, o [MediaExtractor]. */
internal interface FonteDeAmostras {
    fun ler(buffer: ByteBuffer): Int

    fun instante(): Long

    fun avancar()
}

/** Dados de um buffer de saída: espelha o BufferInfo do MediaCodec sem depender dele no laço. */
internal class InfoDoBuffer {
    var offset = 0
    var size = 0
    var flags = 0
}

/** Taxa e canais do formato de saída, quando o decodificador os informa. */
internal class FormatoDeSaida(val taxa: Int?, val canais: Int?)

/** PCM decodificado (16 bits intercalado) e o formato em que ele saiu. */
internal class AudioDecodificado(val pcm: ByteArray, val taxa: Int, val canais: Int)

/** O que decodificar precisa: as duas portas e o formato inicial da faixa. */
internal class Decodificacao(
    val decodificador: Decodificador,
    val fonte: FonteDeAmostras,
    val taxaInicial: Int,
    val canaisInicial: Int
)

/** Decodificador de produção: repassa tudo ao [MediaCodec]. */
private class DecodificadorDoAndroid(
    private val codec: MediaCodec,
    private val formato: MediaFormat
) : Decodificador {
    private val infoDoCodec = MediaCodec.BufferInfo()

    override fun iniciar() {
        codec.configure(formato, null, null, 0)
        codec.start()
    }

    override fun desenfileirarEntrada(esperaUs: Long): Int = codec.dequeueInputBuffer(esperaUs)

    override fun desenfileirarSaida(info: InfoDoBuffer, esperaUs: Long): Int {
        val indice = codec.dequeueOutputBuffer(infoDoCodec, esperaUs)
        if (indice >= 0) {
            info.offset = infoDoCodec.offset
            info.size = infoDoCodec.size
            info.flags = infoDoCodec.flags
        }
        return indice
    }

    override fun bufferDeEntrada(indice: Int): ByteBuffer? = codec.getInputBuffer(indice)

    override fun bufferDeSaida(indice: Int): ByteBuffer? = codec.getOutputBuffer(indice)

    override fun enfileirarEntrada(indice: Int, tamanho: Int, instanteUs: Long, flags: Int) {
        codec.queueInputBuffer(indice, 0, tamanho, instanteUs, flags)
    }

    override fun liberarSaida(indice: Int) = codec.releaseOutputBuffer(indice, false)

    override val formatoDeSaida: FormatoDeSaida
        get() = codec.outputFormat.let { novo ->
            FormatoDeSaida(
                taxa = if (novo.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    novo.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    null
                },
                canais = if (novo.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    novo.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    null
                }
            )
        }

    override fun parar() = codec.stop()

    override fun liberar() = codec.release()
}

/** Embrulha o codec num [Decodificador] para o laço. */
internal fun decodificadorDoAndroid(codec: MediaCodec, formato: MediaFormat): Decodificador =
    DecodificadorDoAndroid(codec, formato)

/** Lê as amostras comprimidas pelo [extractor] para o laço. */
internal fun fonteDoExtractor(extractor: MediaExtractor): FonteDeAmostras = object : FonteDeAmostras {
    override fun ler(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)

    override fun instante(): Long = extractor.sampleTime

    override fun avancar() {
        extractor.advance()
    }
}

/**
 * Laço de decodificação, isolado do [VoiceReferenceAudio] para poder ser testado com decodificador
 * e relógio falsos. Não para nem libera o decodificador: isso é de quem o criou.
 */
internal object DecodificadorDeAudio {

    /**
     * Laço de decodificação. Os prazos medem ausência de progresso: sem buffer de saída por
     * [SEM_PROGRESSO_MS], ou passando de [TETO_TOTAL_MS], encerra e devolve o que já decodificou.
     * Sem nada decodificado, lança erro claro em vez de gravar um WAV vazio.
     */
    fun decodificar(
        decodificacao: Decodificacao,
        relogio: () -> Long = { SystemClock.elapsedRealtime() }
    ): AudioDecodificado {
        var taxa = decodificacao.taxaInicial
        var canais = decodificacao.canaisInicial
        val pcm = ByteArrayOutputStream()
        val info = InfoDoBuffer()
        var fimEntrada = false
        var fimSaida = false
        // Teto de bytes: o áudio além disso é cortado depois, na escolha do trecho.
        val limiteBytes = SEGUNDOS_DECODIFICADOS * taxa * canais * 2
        val inicio = relogio()
        var ultimoProgresso = inicio
        while (!fimSaida && pcm.size() < limiteBytes) {
            val agora = relogio()
            if (agora - ultimoProgresso >= SEM_PROGRESSO_MS || agora - inicio >= TETO_TOTAL_MS) break
            if (!fimEntrada) fimEntrada = enfileirar(decodificacao)
            val indice = decodificacao.decodificador.desenfileirarSaida(info, ESPERA_US)
            when {
                indice >= 0 -> {
                    pcm.write(lerSaida(decodificacao.decodificador, indice, info))
                    decodificacao.decodificador.liberarSaida(indice)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimSaida = true
                    // Progrediu: o prazo de ausência de progresso recomeça.
                    ultimoProgresso = relogio()
                }
                indice == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val novo = decodificacao.decodificador.formatoDeSaida
                    taxa = novo.taxa ?: taxa
                    canais = novo.canais ?: canais
                }
            }
        }
        if (pcm.size() == 0) {
            error("O decodificador não devolveu nenhum trecho de áudio.")
        }
        return AudioDecodificado(pcm.toByteArray(), taxa, canais)
    }

    /** Enfileira uma amostra comprimida. Devolve true quando a fonte chegou ao fim. */
    private fun enfileirar(decodificacao: Decodificacao): Boolean {
        val indice = decodificacao.decodificador.desenfileirarEntrada(ESPERA_US)
        var chegouAoFim = false
        // Buffer nulo não significa fim do fluxo: a próxima volta tenta de novo.
        val buffer = if (indice >= 0) decodificacao.decodificador.bufferDeEntrada(indice) else null
        if (buffer != null) {
            val n = decodificacao.fonte.ler(buffer)
            if (n < 0) {
                decodificacao.decodificador.enfileirarEntrada(indice, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                chegouAoFim = true
            } else {
                decodificacao.decodificador.enfileirarEntrada(indice, n, decodificacao.fonte.instante(), 0)
                decodificacao.fonte.avancar()
            }
        }
        return chegouAoFim
    }

    /** Copia os bytes do buffer de saída para o PCM. */
    private fun lerSaida(decodificador: Decodificador, indice: Int, info: InfoDoBuffer): ByteArray {
        val saida = decodificador.bufferDeSaida(indice)
            ?: error("Buffer de saída indisponível na decodificação do áudio.")
        val bytes = ByteArray(info.size)
        saida.position(info.offset)
        saida.get(bytes, 0, info.size)
        return bytes
    }
}
