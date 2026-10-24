package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.jonjonesbr.audiobookgen.R
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodifica o áudio de origem (ex.: MP3) e reencoda pra AAC — [android.media.MediaMuxer] só
 * grava áudio AAC em contêiner MP4, não aceita MP3 direto.
 *
 * Em pipeline de duas threads: uma decodifica (extrator → decoder → PCM) e a outra codifica (PCM → encoder AAC), ligadas por uma
 * fila limitada. Os codecs de software do Android atendem um quadro por chamada (~1 ms de ida e volta cada); em sequência, as duas
 * esperas se somavam (o áudio levava ~3–5× o tempo real num aparelho de entrada). Em pipeline, vale só a etapa mais lenta.
 */
private const val AUDIO_BITRATE = 64_000
private const val BYTES_POR_AMOSTRA_PCM16 = 2
private const val US_POR_SEGUNDO_PCM = 1_000_000L

/** Quantos blocos de PCM o decoder pode adiantar ao encoder (cada um é uma saída do decoder, ~2–4 KB). */
private const val CAPACIDADE_FILA_PCM = 512
private const val ESPERA_CODEC_US = 2_000L
private const val ESPERA_FILA_MS = 5L
private const val ESPERA_JOIN_MS = 5_000L

/** Saída do decoder já copiada para fora do codec; [fim] marca o último bloco (pode vir vazio). */
private class BlocoPcm(
    val bytes: ByteArray,
    val ptsUs: Long,
    val canais: Int,
    val taxa: Int,
    val pcm16: Boolean,
    val fim: Boolean
)

/** As amostras AAC vão para [estado] (use [EstadoCodificacao.aoReceberAmostra] para gravar em fluxo). */
internal fun transcodificarAudioParaAac(
    context: Context,
    audioFile: File,
    duracaoUs: Long,
    estado: EstadoCodificacao,
    onProgress: (Int) -> Unit
) {
    val extractor = MediaExtractor()
    extractor.setDataSource(audioFile.absolutePath)
    val (decoder, encoder) = prepararCodecsDeAudio(context, extractor)
    val fila = ArrayBlockingQueue<BlocoPcm>(CAPACIDADE_FILA_PCM)
    val parar = AtomicBoolean(false)
    val erroDecoder = AtomicReference<Throwable?>(null)
    val threadDecoder = Thread({ rodarDecoder(extractor, decoder, fila, parar, erroDecoder) }, "lyly-decoder-audio")
    try {
        threadDecoder.start()
        rodarEncoder(context, encoder, fila, estado, duracaoUs, erroDecoder, onProgress)
    } finally {
        parar.set(true)
        threadDecoder.join(ESPERA_JOIN_MS)
        pararComSeguranca(decoder)
        pararComSeguranca(encoder)
        extractor.release()
    }
}

private fun prepararCodecsDeAudio(context: Context, extractor: MediaExtractor): Pair<MediaCodec, MediaCodec> {
    val trackIdx = indiceTrilhaAudio(extractor) ?: error(context.getString(R.string.audio_sem_trilha))
    extractor.selectTrack(trackIdx)
    val formatoEntrada = extractor.getTrackFormat(trackIdx)
    val mimeEntrada = formatoEntrada.getString(MediaFormat.KEY_MIME)
        ?: error(context.getString(R.string.audio_trilha_sem_mime))
    val decoder = MediaCodec.createDecoderByType(mimeEntrada).apply {
        configure(formatoEntrada, null, null, 0)
        start()
    }
    return decoder to criarEncoderAac(formatoEntrada)
}

private fun criarEncoderAac(formatoEntrada: MediaFormat): MediaCodec {
    val sampleRate = formatoEntrada.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channelCount = formatoEntrada.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val formatoAac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }
    return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(formatoAac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Thread do decoder: extrator → decoder → fila de PCM
// ---------------------------------------------------------------------------------------------------------------------------

private fun rodarDecoder(
    extractor: MediaExtractor,
    decoder: MediaCodec,
    fila: ArrayBlockingQueue<BlocoPcm>,
    parar: AtomicBoolean,
    erro: AtomicReference<Throwable?>
) {
    try {
        val info = MediaCodec.BufferInfo()
        var entradaConcluida = false
        var saidaConcluida = false
        var formato: MediaFormat? = null
        while (!saidaConcluida && !parar.get()) {
            while (!entradaConcluida) {
                val resultado = alimentarDecoder(extractor, decoder)
                if (resultado == ResultadoEntrada.SEM_BUFFER) break
                entradaConcluida = resultado == ResultadoEntrada.FIM
            }
            var idx = decoder.dequeueOutputBuffer(info, ESPERA_CODEC_US)
            while (idx != MediaCodec.INFO_TRY_AGAIN_LATER && !saidaConcluida) {
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formato = decoder.outputFormat
                } else if (idx >= 0) {
                    val fmt = formato ?: decoder.outputFormat.also { formato = it }
                    val fim = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val bloco = copiarSaida(decoder, idx, info, fmt, fim)
                    decoder.releaseOutputBuffer(idx, false)
                    if (!entregarNaFila(fila, bloco, parar)) return
                    saidaConcluida = fim
                }
                if (!saidaConcluida) idx = decoder.dequeueOutputBuffer(info, 0)
            }
        }
    } catch (e: Throwable) {
        erro.set(e)
    }
}

private enum class ResultadoEntrada { ALIMENTOU, FIM, SEM_BUFFER }

/** Passa uma amostra do extrator ao decoder (sem esperar). [ResultadoEntrada.FIM] = o fim do fluxo foi sinalizado. */
private fun alimentarDecoder(extractor: MediaExtractor, decoder: MediaCodec): ResultadoEntrada {
    val inIdx = decoder.dequeueInputBuffer(0)
    if (inIdx < 0) return ResultadoEntrada.SEM_BUFFER
    val buffer = decoder.getInputBuffer(inIdx) ?: return ResultadoEntrada.SEM_BUFFER
    val tamanho = extractor.readSampleData(buffer, 0)
    if (tamanho < 0) {
        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return ResultadoEntrada.FIM
    }
    decoder.queueInputBuffer(inIdx, 0, tamanho, extractor.sampleTime, 0)
    extractor.advance()
    return ResultadoEntrada.ALIMENTOU
}

private fun copiarSaida(decoder: MediaCodec, idx: Int, info: MediaCodec.BufferInfo, formato: MediaFormat, fim: Boolean): BlocoPcm {
    val bytes = ByteArray(info.size)
    if (info.size > 0) {
        decoder.getOutputBuffer(idx)?.let { buffer ->
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(bytes)
        }
    }
    val encoding = if (formato.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        formato.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }
    return BlocoPcm(
        bytes = bytes,
        ptsUs = info.presentationTimeUs,
        canais = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        taxa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        pcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT,
        fim = fim
    )
}

/** Coloca o bloco na fila, esperando se estiver cheia; false = a exportação foi interrompida. */
private fun entregarNaFila(fila: ArrayBlockingQueue<BlocoPcm>, bloco: BlocoPcm, parar: AtomicBoolean): Boolean {
    while (!parar.get()) {
        if (fila.offer(bloco, ESPERA_FILA_MS, TimeUnit.MILLISECONDS)) return true
    }
    return false
}

// ---------------------------------------------------------------------------------------------------------------------------
// Thread chamadora: fila de PCM → encoder AAC → amostras
// ---------------------------------------------------------------------------------------------------------------------------

/** Bloco do decoder em entrega ao encoder: o buffer de entrada do encoder (~8 KB) é menor que vários blocos somados, então o bloco pode ir em pedaços. */
private class EntregaEmCurso {
    var bloco: BlocoPcm? = null
    var deslocamento = 0
}

@Suppress("LongParameterList")
private fun rodarEncoder(
    context: Context,
    encoder: MediaCodec,
    fila: ArrayBlockingQueue<BlocoPcm>,
    estado: EstadoCodificacao,
    duracaoUs: Long,
    erroDecoder: AtomicReference<Throwable?>,
    onProgress: (Int) -> Unit
) {
    val entrega = EntregaEmCurso()
    var fimEnviado = false
    var concluido = false
    var iteracoes = 0
    while (!concluido && iteracoes < MAX_ITERACOES_SEGURANCA) {
        iteracoes++
        if (estado.cancelado()) throw CancellationException("Exportacao cancelada")
        erroDecoder.get()?.let { throw IllegalStateException(context.getString(R.string.audio_falha_decodificar, it.message), it) }
        var andou = false
        while (!fimEnviado) {
            val bloco = entrega.bloco ?: fila.poll()?.also { entrega.bloco = it; entrega.deslocamento = 0 } ?: break
            if (entrega.deslocamento < bloco.bytes.size && !entregarPedaco(encoder, entrega, bloco, estado)) break
            if (entrega.deslocamento >= bloco.bytes.size) {
                andou = true
                if (bloco.fim) {
                    if (!sinalizarFimDeEntrada(encoder)) break
                    fimEnviado = true
                }
                entrega.bloco = null
            }
        }
        val drenagem = escoarTudoDoEncoder(encoder, estado)
        concluido = drenagem.terminou
        if (drenagem.houveSaida) andou = true
        // Nada andou: espera pelo estágio atrasado — o decoder (fila vazia) ou o encoder (entrada cheia) — em vez de girar à toa.
        if (!andou && !concluido) {
            if (!fimEnviado && entrega.bloco == null) {
                fila.poll(ESPERA_FILA_MS, TimeUnit.MILLISECONDS)?.let { entrega.bloco = it; entrega.deslocamento = 0 }
            } else {
                concluido = escoarSaidaEncoder(encoder, estado)
            }
        }
        reportarProgresso(estado, duracaoUs, onProgress)
    }
    check(concluido) { "Transcodificacao de audio nao terminou (timeout de seguranca)" }
}

/**
 * Entrega ao encoder o próximo pedaço do bloco, alinhado ao quadro de PCM (canais × 2 bytes) e com o tempo calculado pelo que já
 * foi entregue. Retorna false se o encoder está sem buffer de entrada (tentar de novo depois de drenar a saída).
 */
private fun entregarPedaco(encoder: MediaCodec, entrega: EntregaEmCurso, bloco: BlocoPcm, estado: EstadoCodificacao): Boolean {
    val encIdx = encoder.dequeueInputBuffer(0)
    if (encIdx < 0) return false
    val encBuf = encoder.getInputBuffer(encIdx) ?: return false
    val bytesPorQuadro = bloco.canais * BYTES_POR_AMOSTRA_PCM16
    val n = tamanhoAlinhado(encBuf.capacity(), bloco.bytes.size - entrega.deslocamento, bytesPorQuadro)
    encBuf.clear()
    encBuf.put(bloco.bytes, entrega.deslocamento, n)
    val pts = ptsDoPedaco(bloco.ptsUs, entrega.deslocamento, bloco.taxa, bloco.canais)
    estado.aoDecodificarPcm?.let { entregarPcm(bloco, entrega.deslocamento, n, it) }
    encoder.queueInputBuffer(encIdx, 0, n, pts, 0)
    entrega.deslocamento += n
    return true
}

/**
 * Tamanho do pedaço a entregar: o menor entre a capacidade do buffer e o que falta, arredondado para baixo ao quadro de PCM.
 * Os ramos sem alinhamento (capacidade ou resto menor que um quadro) não ocorrem na prática: o buffer do encoder AAC tem ≥ 8 KB e o
 * decoder entrega quadros inteiros. Se ocorressem, o erro ficaria restrito ao bloco, porque o pts recomeça em `bloco.ptsUs`.
 */
internal fun tamanhoAlinhado(capacidade: Int, restante: Int, bytesPorQuadro: Int): Int {
    var n = minOf(capacidade, restante)
    if (n > bytesPorQuadro) n -= n % bytesPorQuadro
    return n
}

/** Tempo (us) do pedaço que começa [deslocamentoBytes] depois do início do bloco, a partir do tempo do bloco [ptsBaseUs]. */
internal fun ptsDoPedaco(ptsBaseUs: Long, deslocamentoBytes: Int, taxa: Int, canais: Int): Long {
    val bytesPorQuadro = canais * BYTES_POR_AMOSTRA_PCM16
    return ptsBaseUs + deslocamentoBytes * US_POR_SEGUNDO_PCM / (taxa.toLong() * bytesPorQuadro)
}

// Só entrega o PCM quando o formato é PCM 16 bits (a linha do tempo da análise de silêncios acompanha o áudio gravado).
private fun entregarPcm(bloco: BlocoPcm, deslocamento: Int, tamanho: Int, aoDecodificarPcm: (ShortArray, Int, Int) -> Unit) {
    if (!bloco.pcm16) return
    val shorts = ShortArray(tamanho / BYTES_POR_AMOSTRA_PCM16)
    ByteBuffer.wrap(bloco.bytes, deslocamento, tamanho).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
    aoDecodificarPcm(shorts, bloco.canais, bloco.taxa)
}

/** Retorna true se o encoder aceitou o sinal de fim (false = sem buffer disponivel, tentar de novo). */
private fun sinalizarFimDeEntrada(encoder: MediaCodec): Boolean {
    val encInIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
    if (encInIdx < 0) return false
    encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    return true
}

// ---------------------------------------------------------------------------------------------------------------------------
// Plano B (WAV disfarçado de .mp3): PCM 16 bits lido do próprio arquivo, sem MediaExtractor nem decoder.
// ---------------------------------------------------------------------------------------------------------------------------

/**
 * Lê [tamanhoDados] bytes de PCM 16 bits a partir de [offsetDados] do arquivo, em blocos (streaming), e grava AAC num MP4
 * de uma trilha em [saida]. Usa o mesmo encoder e a mesma drenagem da transcodificação normal. [interrompido] é consultado
 * a cada volta; quando devolve true, lança CancellationException. Qualquer falha propaga: quem chama apaga o arquivo parcial.
 */
internal fun codificarPcm16ParaMp4(
    arquivo: RandomAccessFile,
    offsetDados: Long,
    tamanhoDados: Long,
    canais: Int,
    taxa: Int,
    saida: File,
    interrompido: () -> Boolean
) {
    val bytesPorQuadro = canais * BYTES_POR_AMOSTRA_PCM16
    val formatoPcm = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, taxa, canais)
    val encoder = criarEncoderAac(formatoPcm)
    val estado = EstadoCodificacao()
    estado.cancelado = interrompido
    var muxer: MediaMuxer? = null
    var trilha = -1
    estado.aoReceberAmostra = { amostra ->
        if (muxer == null) {
            val formato = formatoComCsdGarantido(estado, "Encoder AAC nao reportou formato de saida")
            muxer = MediaMuxer(saida.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                trilha = it.addTrack(formato)
                it.start()
            }
        }
        val info = MediaCodec.BufferInfo()
        info.set(0, amostra.bytes.size, amostra.presentationTimeUs, amostra.flags)
        muxer?.writeSampleData(trilha, ByteBuffer.wrap(amostra.bytes), info)
    }
    val bloco = ByteArray(bytesPorQuadro * 4096)
    var restante = tamanhoDados
    var bytesEnviados = 0L
    var eosEnviado = false
    var terminou = false
    var iteracoes = 0
    try {
        arquivo.seek(offsetDados)
        while (!terminou) {
            check(iteracoes++ < MAX_ITERACOES_SEGURANCA) { "Codificacao de audio nao terminou (timeout de seguranca)" }
            if (interrompido()) throw CancellationException("Compressao de audio cancelada")
            var enviou = false
            if (!eosEnviado) {
                val inIdx = encoder.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    val encBuf = encoder.getInputBuffer(inIdx) ?: error("Buffer de entrada do encoder indisponivel")
                    val pts = (bytesEnviados / bytesPorQuadro) * US_POR_SEGUNDO_PCM / taxa
                    val lido = if (restante > 0) lerAlinhado(arquivo, bloco, encBuf.capacity(), restante, bytesPorQuadro) else 0
                    if (lido > 0) {
                        encBuf.clear()
                        encBuf.put(bloco, 0, lido)
                        encoder.queueInputBuffer(inIdx, 0, lido, pts, 0)
                        bytesEnviados += lido
                        restante -= lido
                        enviou = true
                    } else {
                        encoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosEnviado = true
                    }
                }
            }
            val drenagem = escoarTudoDoEncoder(encoder, estado)
            if (drenagem.terminou) {
                terminou = true
            } else if (!drenagem.houveSaida && !enviou && escoarSaidaEncoder(encoder, estado)) {
                terminou = true
            }
        }
        (muxer ?: error("Nenhuma amostra AAC foi gerada")).stop()
    } finally {
        pararComSeguranca(encoder)
        runCatching { muxer?.release() }
    }
}

/**
 * Lê no máximo o que cabe no buffer do encoder e no bloco, arredondado ao quadro de PCM. Devolve os bytes lidos
 * (0 = fim dos dados do WAV ou arquivo truncado).
 */
internal fun lerAlinhado(arquivo: RandomAccessFile, bloco: ByteArray, capacidadeEncoder: Int, restante: Long, bytesPorQuadro: Int): Int {
    val limite = minOf(capacidadeEncoder.toLong(), restante, bloco.size.toLong()).toInt()
    val pedido = maxOf(bytesPorQuadro, limite - limite % bytesPorQuadro)
    val alvo = minOf(pedido.toLong(), restante).toInt()
    // read() pode devolver menos que o pedido: lê até completar o alvo ou acabar o arquivo.
    var lido = 0
    while (lido < alvo) {
        val r = arquivo.read(bloco, lido, alvo - lido)
        if (r <= 0) break
        lido += r
    }
    val alinhado = lido - lido % bytesPorQuadro
    // Sobra de quadro incompleto (só no fim do arquivo): volta a posição para não desalinhar a próxima leitura.
    if (lido > alinhado) arquivo.seek(arquivo.filePointer - (lido - alinhado))
    return alinhado
}
