package com.jonjonesbr.audiobookgen.domain

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * Tipos e funções compartilhados entre [transcodificarAudioParaAac] e
 * [codificarQuadrosEstaticosH264] (as duas trilhas do MP4 gerado por [VideoExportUseCase]) —
 * ambos são um loop de MediaCodec com a mesma forma de drenagem de saída.
 */
internal const val CODEC_TIMEOUT_US = 10_000L
internal const val MAX_ITERACOES_SEGURANCA = 3_000_000
internal const val PROGRESSO_MAX = 100

/** Resoluções 16:9 do vídeo exportado — a capa é estática, então quanto menor, mais barato (padrão 240p). */
enum class ResolucaoVideo(val largura: Int, val altura: Int) {
    P144(256, 144),
    P240(432, 240),
    P360(640, 360),
    P480(848, 480),
    P720(1280, 720)
}

internal data class AmostraCodificada(val bytes: ByteArray, val presentationTimeUs: Long, val flags: Int)

internal data class TrilhaCodificada(val format: MediaFormat, val amostras: List<AmostraCodificada>)

internal class EstadoCodificacao {
    var formatoSaida: MediaFormat? = null
    var configBytes: ByteArray? = null
    val amostras = mutableListOf<AmostraCodificada>()
    var ultimoPtsUs = 0L
    var ultimoPctReportado = -1

    /** Se definido, cada amostra é entregue aqui em vez de acumulada em [amostras] (gravação em fluxo). */
    var aoReceberAmostra: ((AmostraCodificada) -> Unit)? = null

    /** Se definido, recebe o PCM 16 bits decodificado (intercalado, canais, taxa) — usado para achar silêncios. */
    var aoDecodificarPcm: ((ShortArray, Int, Int) -> Unit)? = null

    /** Consultado a cada volta do laço; verdadeiro interrompe a transcodificação. */
    var cancelado: () -> Boolean = { false }
}

/**
 * Formato final da trilha, garantindo csd-0 (SPS/PPS ou equivalente) mesmo em encoders que não
 * o incluem no MediaFormat de INFO_OUTPUT_FORMAT_CHANGED — confirmado em teste real de
 * dispositivo (encoder de hardware MediaTek) que entrega esses bytes só via um buffer de saida
 * separado (BUFFER_FLAG_CODEC_CONFIG). Sem isso o MediaMuxer grava uma trilha de video sem
 * SPS/PPS, que qualquer player reporta como codec desconhecido (largura/altura zeradas).
 */
internal fun formatoComCsdGarantido(estado: EstadoCodificacao, mensagemErro: String): MediaFormat {
    val formato = estado.formatoSaida ?: error(mensagemErro)
    val config = estado.configBytes
    if (config != null && !formato.containsKey("csd-0")) {
        formato.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(config))
    }
    return formato
}

internal fun indiceTrilhaAudio(extractor: MediaExtractor): Int? {
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) return i
    }
    return null
}

/** Drena a saída de um encoder (áudio ou vídeo) — retorna true quando o encoder terminou (EOS). */
internal fun escoarSaidaEncoder(encoder: MediaCodec, estado: EstadoCodificacao): Boolean {
    val info = MediaCodec.BufferInfo()
    val outIdx = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
    return when {
        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            estado.formatoSaida = encoder.outputFormat
            false
        }
        outIdx < 0 -> false
        else -> processarAmostraDeSaida(encoder, outIdx, info, estado)
    }
}

/** [terminou]: o encoder entregou o fim do fluxo; [houveSaida]: algo foi drenado nesta chamada. */
internal class ResultadoDrenagem(val terminou: Boolean, val houveSaida: Boolean)

/** Drena TUDO que o encoder já tem pronto, sem esperar (timeout 0) — não deixa a saída acumular enquanto a entrada é alimentada. */
internal fun escoarTudoDoEncoder(encoder: MediaCodec, estado: EstadoCodificacao): ResultadoDrenagem {
    val info = MediaCodec.BufferInfo()
    var houve = false
    while (true) {
        val idx = encoder.dequeueOutputBuffer(info, 0)
        when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                estado.formatoSaida = encoder.outputFormat
                houve = true
            }
            idx < 0 -> return ResultadoDrenagem(false, houve)
            else -> {
                houve = true
                if (processarAmostraDeSaida(encoder, idx, info, estado)) return ResultadoDrenagem(true, true)
            }
        }
    }
}

private fun processarAmostraDeSaida(
    encoder: MediaCodec,
    outIdx: Int,
    info: MediaCodec.BufferInfo,
    estado: EstadoCodificacao
): Boolean {
    val ehConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
    if (info.size > 0) {
        encoder.getOutputBuffer(outIdx)?.let { buffer ->
            val bytes = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(bytes)
            if (ehConfig) {
                estado.configBytes = bytes
            } else {
                val flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                val amostra = AmostraCodificada(bytes, info.presentationTimeUs, flags)
                estado.ultimoPtsUs = info.presentationTimeUs
                estado.aoReceberAmostra?.invoke(amostra) ?: estado.amostras.add(amostra)
            }
        }
    }
    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
    encoder.releaseOutputBuffer(outIdx, false)
    return isEos
}

internal fun reportarProgresso(estado: EstadoCodificacao, duracaoUs: Long, onProgress: (Int) -> Unit) {
    val pct = ((estado.ultimoPtsUs * PROGRESSO_MAX) / duracaoUs).toInt().coerceIn(0, PROGRESSO_MAX)
    if (pct == estado.ultimoPctReportado) return
    estado.ultimoPctReportado = pct
    onProgress(pct)
}

internal fun pararComSeguranca(codec: MediaCodec) {
    runCatching { codec.stop() }
    codec.release()
}
