package com.jonjonesbr.audiobookgen.domain

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Codifica a imagem estática em H.264 uma única vez (2 quadros-chave). O MP4 final repete essas
 * amostras a cada [INTERVALO_QUADRO_VIDEO_US] ([VideoExportUseCase]) — o custo do vídeo some,
 * em vez de codificar um quadro por intervalo ao longo de horas de áudio.
 */
private const val VIDEO_BITRATE = 150_000
private const val VIDEO_FRAME_RATE = 1
private const val VIDEO_TODOS_QUADROS_CHAVE = 0
private const val QUADROS_CODIFICADOS = 2
internal const val INTERVALO_QUADRO_VIDEO_US = 5_000_000L

// Tamanho total do buffer YUV420 (Y + U + V) para o frame de entrada — confirmado em teste
// real de dispositivo que passar size=0 pro queueInputBuffer (mesmo preenchendo a Image via
// getInputImage()) faz o encoder de hardware descartar o frame silenciosamente, sem erro:
// a trilha de video saia do MediaMuxer com a stbl vazia (zero amostras), apesar da exportacao
// "concluir com sucesso".
private fun tamanhoBufferYuv420(yuv: Yuv420) = yuv.largura * yuv.altura * 3 / 2

private class EstadoAvancoVideo {
    var quadrosEnviados = 0
    var entradaConcluida = false
}

/**
 * Devolve o formato da trilha e 1 ou 2 amostras-chave para alternar: com dois quadros-chave
 * consecutivos o `idr_pic_id` difere (exigência do H.264); se o encoder não gerar o segundo como
 * chave, repete-se só o primeiro.
 */
internal fun codificarQuadrosEstaticosH264(bitmap: Bitmap): TrilhaCodificada {
    val encoder = criarEncoderVideo(bitmap.width, bitmap.height)
    val yuv = converterBitmapParaYuv420(bitmap)
    val estado = EstadoCodificacao()
    try {
        rodarLoopDeCodificacaoDeVideo(encoder, yuv, estado)
    } finally {
        pararComSeguranca(encoder)
    }
    val formatoFinal = formatoComCsdGarantido(estado, "Encoder de video nao reportou formato de saida")
    val chaves = estado.amostras.filter { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
    check(chaves.isNotEmpty()) { "Encoder de video nao gerou quadro-chave" }
    return TrilhaCodificada(formatoFinal, chaves.take(QUADROS_CODIFICADOS))
}

private fun rodarLoopDeCodificacaoDeVideo(encoder: MediaCodec, yuv: Yuv420, estado: EstadoCodificacao) {
    val avanco = EstadoAvancoVideo()
    var iteracoes = 0
    var encoderConcluido = false
    while (!encoderConcluido && iteracoes < MAX_ITERACOES_SEGURANCA) {
        iteracoes++
        avancarEntradaSeNecessario(encoder, yuv, avanco)
        encoderConcluido = escoarSaidaEncoder(encoder, estado)
    }
    check(encoderConcluido) { "Codificacao de video nao terminou (timeout de seguranca)" }
}

private enum class ResultadoAlimentacao { SEM_BUFFER_DISPONIVEL, FRAME_ENVIADO, FIM_DE_STREAM }

// dequeueInputBuffer() pode retornar timeout (sem buffer disponivel) mesmo com frames ainda
// por enviar — só conta o quadro como enviado quando ele de fato entrou no encoder.
private fun avancarEntradaSeNecessario(encoder: MediaCodec, yuv: Yuv420, estado: EstadoAvancoVideo) {
    if (estado.entradaConcluida) return
    when (alimentarProximoFrame(encoder, yuv, estado.quadrosEnviados)) {
        ResultadoAlimentacao.SEM_BUFFER_DISPONIVEL -> Unit
        ResultadoAlimentacao.FRAME_ENVIADO -> estado.quadrosEnviados++
        ResultadoAlimentacao.FIM_DE_STREAM -> estado.entradaConcluida = true
    }
}

private fun criarEncoderVideo(largura: Int, altura: Int): MediaCodec {
    val formato = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, largura, altura).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_TODOS_QUADROS_CHAVE)
    }
    return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
}

/** Alimenta um frame (mesma imagem, timestamp avançado) ou sinaliza fim. */
private fun alimentarProximoFrame(encoder: MediaCodec, yuv: Yuv420, indice: Int): ResultadoAlimentacao {
    val inIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
    if (inIdx < 0) return ResultadoAlimentacao.SEM_BUFFER_DISPONIVEL
    return if (indice >= QUADROS_CODIFICADOS) {
        encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        ResultadoAlimentacao.FIM_DE_STREAM
    } else {
        // getInputImage() pode retornar null em alguns encoders de hardware mesmo com
        // COLOR_FormatYUV420Flexible configurado — falhar alto em vez de enviar um buffer
        // vazio silenciosamente (que gerava um MP4 com o video track corrompido).
        val image = encoder.getInputImage(inIdx)
            ?: error("Encoder de video nao expos Image de entrada (getInputImage retornou null)")
        escreverYuvNaImagem(image, yuv)
        encoder.queueInputBuffer(inIdx, 0, tamanhoBufferYuv420(yuv), indice * INTERVALO_QUADRO_VIDEO_US, 0)
        ResultadoAlimentacao.FRAME_ENVIADO
    }
}
